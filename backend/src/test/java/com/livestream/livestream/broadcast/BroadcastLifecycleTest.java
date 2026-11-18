package com.livestream.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.RecordingStatus;
import com.livestream.livestream.recording.Visibility;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

/**
 * BC-1 통합 테스트 (DESIGN §9 BC-1): MediaMTX 훅 → 상태 전이, 유예 종료, 소유자 종료 + kick, 인증 콜백, 내부 토큰.
 * MediaMTX Control API 는 mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastLifecycleTest {

	private static final String PASSWORD = "password123";

	@Autowired MockMvc mvc;
	@Autowired AppProperties props;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired RecordingRepository recordingRepository;
	@Autowired DisconnectGraceScheduler graceScheduler;
	@Autowired LiveBroadcastReconciler reconciler;
	@Autowired BroadcastLifecycleService lifecycle;
	@Autowired PublishBlockRegistry publishBlockRegistry;
	@Autowired EntityManager em;
	@Autowired TransactionTemplate tx;
	@MockitoBean MediaMtxClient mediaMtxClient;

	record Account(String access, long channelId, String streamKey) {
	}

	// ---------- 훅 → 상태 전이 ----------

	@Test
	void ready_훅이_오면_LIVE_방송과_녹화가_생기고_채널_정보를_복사한다() throws Exception {
		Account a = newAccount();
		patchChannel(a, "{\"title\":\"첫 방송\",\"category\":\"GAME\"}");

		ready(a, "webRTCSession", "src=pc").andExpect(status().isNoContent());

		Broadcast b = ongoing(a);
		assertThat(b.getStatus()).isEqualTo(BroadcastStatus.LIVE);
		assertThat(b.getSource()).isEqualTo(BroadcastSource.BROWSER_PC);
		assertThat(b.getTitle()).isEqualTo("첫 방송");
		assertThat(b.getCategory().name()).isEqualTo("GAME");
		assertThat(b.getStartedAt()).isNotNull();
		Recording r = recordingRepository.findByBroadcastId(b.getId()).orElseThrow();
		assertThat(r.getStatus()).isEqualTo(RecordingStatus.RECORDING);
		assertThat(r.getVisibility()).isEqualTo(Visibility.PUBLIC);

		// 채널 응답의 진행 중 방송(CH-1 TODO)
		mvc.perform(get("/api/channels/me").header("Authorization", bearer(a)))
				.andExpect(jsonPath("$.liveBroadcastId").value(b.getId()))
				.andExpect(jsonPath("$.currentBroadcast.id").value(b.getId()))
				.andExpect(jsonPath("$.currentBroadcast.status").value("LIVE"));
		mvc.perform(get("/api/channels/" + a.channelId())).andExpect(jsonPath("$.liveBroadcastId").value(b.getId()));
	}

	@Test
	void 송출_수단은_sourceType과_src_쿼리로_정한다() throws Exception {
		assertSource("rtmpConn", "user=obs&pass=x", BroadcastSource.STREAM_KEY);
		assertSource("webRTCSession", "src=mobile", BroadcastSource.BROWSER_MOBILE);
		assertSource("webRTCSession", "src=obs", BroadcastSource.STREAM_KEY);
		assertSource("webRTCSession", "", BroadcastSource.BROWSER_PC);
	}

	@Test
	void 자동_공개를_끈_채널의_녹화는_PRIVATE() throws Exception {
		Account a = newAccount();
		patchChannel(a, "{\"autoPublishRecording\":false}");
		ready(a, "webRTCSession", "src=pc");
		assertThat(recordingRepository.findByBroadcastId(ongoing(a).getId()).orElseThrow().getVisibility())
				.isEqualTo(Visibility.PRIVATE);
	}

	@Test
	void not_ready는_DISCONNECTED로_바꾸고_유예_안에_ready가_오면_같은_방송이_LIVE로_돌아온다() throws Exception {
		Account a = newAccount();
		ready(a, "webRTCSession", "src=pc");
		Long id = ongoing(a).getId();

		notReady(a).andExpect(status().isNoContent());
		Broadcast disconnected = ongoing(a);
		assertThat(disconnected.getStatus()).isEqualTo(BroadcastStatus.DISCONNECTED);
		assertThat(disconnected.getDisconnectedAt()).isNotNull();
		notReady(a).andExpect(status().isNoContent()); // 멱등
		mvc.perform(get("/api/channels/me").header("Authorization", bearer(a)))
				.andExpect(jsonPath("$.currentBroadcast.status").value("DISCONNECTED"));

		ready(a, "webRTCSession", "src=mobile");
		Broadcast resumed = ongoing(a);
		assertThat(resumed.getId()).isEqualTo(id);
		assertThat(resumed.getStatus()).isEqualTo(BroadcastStatus.LIVE);
		assertThat(resumed.getDisconnectedAt()).isNull();
		assertThat(resumed.getSource()).isEqualTo(BroadcastSource.BROWSER_MOBILE); // 수단이 바뀌면 갱신
		assertThat(countBroadcasts(a)).isEqualTo(1);
	}

	@Test
	void 유예가_지나면_스케줄러가_ENDED로_바꾸고_녹화는_PROCESSING_송출은_잠시_차단된다() throws Exception {
		Account a = newAccount();
		ready(a, "webRTCSession", "src=pc");
		notReady(a);
		Long id = ongoing(a).getId();

		graceScheduler.endExpired(); // 아직 유예 안 → 그대로
		assertThat(find(id).getStatus()).isEqualTo(BroadcastStatus.DISCONNECTED);

		backdateDisconnect(id, props.broadcast().disconnectGraceSeconds() + 1);
		graceScheduler.endExpired();

		Broadcast ended = find(id);
		assertThat(ended.getStatus()).isEqualTo(BroadcastStatus.ENDED);
		assertThat(ended.getEndedAt()).isNotNull();
		assertThat(recordingRepository.findByBroadcastId(id).orElseThrow().getStatus()).isEqualTo(RecordingStatus.PROCESSING);
		verify(mediaMtxClient, never()).kickPublisher(anyString()); // DISCONNECTED였으니 끊을 송출자가 없다
		auth(publishBody(a, a.streamKey(), null, "src=pc")).andExpect(status().isUnauthorized()); // 종료 뒤 차단
		mvc.perform(get("/api/channels/me").header("Authorization", bearer(a)))
				.andExpect(jsonPath("$.currentBroadcast").value(org.hamcrest.Matchers.nullValue()));

		// 종료 뒤 not-ready · 늦은 스케줄러는 무시
		notReady(a).andExpect(status().isNoContent());
		graceScheduler.endExpired();
		assertThat(find(id).getStatus()).isEqualTo(BroadcastStatus.ENDED);
	}

	@Test
	void 종료_직후_늦게_온_ready는_무시하고_차단이_풀린_뒤_ready는_새_방송을_시작한다() throws Exception {
		Account a = newAccount();
		ready(a, "webRTCSession", "src=pc");
		Long first = ongoing(a).getId();
		end(a, first).andExpect(status().isNoContent());

		ready(a, "webRTCSession", "src=pc").andExpect(status().isNoContent()); // 종료 전 연결의 늦은 훅
		assertThat(broadcastRepository.findOngoing(a.channelId())).isEmpty();
		assertThat(countBroadcasts(a)).isEqualTo(1);

		unblock(a); // end-block-seconds(30초) 경과를 흉내 낸다
		ready(a, "rtmpConn", "");
		Broadcast second = ongoing(a);
		assertThat(second.getId()).isNotEqualTo(first);
		assertThat(second.getSource()).isEqualTo(BroadcastSource.STREAM_KEY);
	}

	@Test
	void 송출자가_아직_있으면_늦게_온_not_ready는_무시한다() throws Exception {
		Account a = newAccount();
		ready(a, "rtmpConn", "");
		when(mediaMtxClient.hasPublisher("live/" + a.channelId())).thenReturn(true); // overridePublisher 로 새 연결이 이미 붙음

		notReady(a).andExpect(status().isNoContent());
		assertThat(ongoing(a).getStatus()).isEqualTo(BroadcastStatus.LIVE);
	}

	@Test
	void MediaMTX_조회가_실패하면_not_ready를_끊김으로_처리한다() throws Exception {
		Account a = newAccount();
		ready(a, "rtmpConn", "");
		when(mediaMtxClient.hasPublisher("live/" + a.channelId())).thenThrow(new ResourceAccessException("down"));

		notReady(a).andExpect(status().isNoContent());
		assertThat(ongoing(a).getStatus()).isEqualTo(BroadcastStatus.DISCONNECTED);
	}

	// ---------- LIVE 방송 대조 (BC-1#1) ----------

	@Test
	void 대조에서_송출이_없는_LIVE_방송은_DISCONNECTED가_되고_유예_뒤_ENDED가_된다() throws Exception {
		Account a = newAccount();
		ready(a, "rtmpConn", "");
		when(mediaMtxClient.listReadyPaths()).thenReturn(java.util.Set.of());
		when(mediaMtxClient.hasPublisher("live/" + a.channelId())).thenReturn(false);

		reconciler.reconcile();
		Broadcast b = ongoing(a);
		assertThat(b.getStatus()).isEqualTo(BroadcastStatus.DISCONNECTED);
		assertThat(b.getDisconnectedAt()).isNotNull();

		reconciler.reconcile(); // 멱등
		assertThat(ongoing(a).getStatus()).isEqualTo(BroadcastStatus.DISCONNECTED);

		backdateDisconnect(b.getId(), props.broadcast().disconnectGraceSeconds() + 5);
		graceScheduler.endExpired();
		assertThat(find(b.getId()).getStatus()).isEqualTo(BroadcastStatus.ENDED);
	}

	@Test
	void 대조에서_송출이_있는_LIVE_방송은_그대로다() throws Exception {
		Account a = newAccount();
		ready(a, "rtmpConn", "");
		when(mediaMtxClient.listReadyPaths()).thenReturn(java.util.Set.of("live/" + a.channelId()));

		reconciler.reconcile();
		assertThat(ongoing(a).getStatus()).isEqualTo(BroadcastStatus.LIVE);
	}

	@Test
	void 대조에서_목록에_없어도_개별_확인에서_송출자가_있으면_그대로다() throws Exception {
		Account a = newAccount();
		ready(a, "rtmpConn", "");
		when(mediaMtxClient.listReadyPaths()).thenReturn(java.util.Set.of());
		when(mediaMtxClient.hasPublisher("live/" + a.channelId())).thenReturn(true);

		reconciler.reconcile();
		assertThat(ongoing(a).getStatus()).isEqualTo(BroadcastStatus.LIVE);
	}

	@Test
	void 대조에서_MediaMTX_호출이_실패하면_아무_방송도_바꾸지_않는다() throws Exception {
		Account a = newAccount();
		ready(a, "rtmpConn", "");
		when(mediaMtxClient.listReadyPaths()).thenThrow(new ResourceAccessException("connection refused"));

		reconciler.reconcile();
		assertThat(ongoing(a).getStatus()).isEqualTo(BroadcastStatus.LIVE);
	}

	@Test
	void 대조에서_한_방송의_개별_확인이_실패해도_나머지는_계속_처리한다() throws Exception {
		Account a = newAccount();
		Account b = newAccount();
		ready(a, "rtmpConn", "");
		ready(b, "rtmpConn", "");
		when(mediaMtxClient.listReadyPaths()).thenReturn(java.util.Set.of());
		when(mediaMtxClient.hasPublisher("live/" + a.channelId())).thenThrow(new ResourceAccessException("down"));
		when(mediaMtxClient.hasPublisher("live/" + b.channelId())).thenReturn(false);

		reconciler.reconcile();
		assertThat(ongoing(a).getStatus()).isEqualTo(BroadcastStatus.LIVE);
		assertThat(ongoing(b).getStatus()).isEqualTo(BroadcastStatus.DISCONNECTED);
	}

	@Test
	void 대조가_보던_방송이_끝나고_새_방송이_시작됐으면_새_방송은_끊지_않는다() throws Exception {
		Account a = newAccount();
		ready(a, "rtmpConn", "");
		Long oldId = ongoing(a).getId();
		tx.executeWithoutResult(s -> em.createQuery("update Broadcast b set b.status = :st where b.id = :id")
				.setParameter("st", BroadcastStatus.ENDED).setParameter("id", oldId).executeUpdate());
		ready(a, "rtmpConn", ""); // 새 방송
		Long newId = ongoing(a).getId();
		assertThat(newId).isNotEqualTo(oldId);

		lifecycle.onStaleLive(a.channelId(), oldId);
		assertThat(ongoing(a).getStatus()).isEqualTo(BroadcastStatus.LIVE);
	}

	@Test
	void 대조와_not_ready_훅이_동시에_돌아도_전이는_한_번이고_예외가_없다() throws Exception {
		Account a = newAccount();
		ready(a, "rtmpConn", "");
		when(mediaMtxClient.listReadyPaths()).thenReturn(java.util.Set.of());
		when(mediaMtxClient.hasPublisher("live/" + a.channelId())).thenReturn(false);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch go = new CountDownLatch(1);
			Callable<Void> reconcile = () -> {
				go.await();
				reconciler.reconcile();
				return null;
			};
			Callable<Void> hook = () -> {
				go.await();
				notReady(a).andExpect(status().isNoContent());
				return null;
			};
			Future<Void> f1 = pool.submit(reconcile);
			Future<Void> f2 = pool.submit(hook);
			go.countDown();
			f1.get();
			f2.get();
		} finally {
			pool.shutdown();
		}
		Broadcast b = ongoing(a);
		assertThat(b.getStatus()).isEqualTo(BroadcastStatus.DISCONNECTED);
		assertThat(countBroadcasts(a)).isEqualTo(1);
	}

	@Test
	void 같은_채널에_ready가_동시에_와도_방송은_하나다() throws Exception {
		Account a = newAccount();
		int n = 4;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			List<Callable<Integer>> calls = java.util.stream.IntStream.range(0, n).<Callable<Integer>>mapToObj(i -> () -> {
				start.await();
				return ready(a, "webRTCSession", "src=pc").andReturn().getResponse().getStatus();
			}).toList();
			List<Future<Integer>> futures = calls.stream().map(pool::submit).toList();
			start.countDown();
			for (Future<Integer> f : futures) {
				assertThat(f.get()).isEqualTo(204);
			}
		} finally {
			pool.shutdownNow();
		}
		assertThat(countBroadcasts(a)).isEqualTo(1);
	}

	@Test
	void 없는_채널이나_잘못된_경로의_훅은_무시한다() throws Exception {
		hook("/ready", "{\"path\":\"live/999999999\",\"sourceType\":\"rtmpConn\",\"query\":\"\"}").andExpect(status().isNoContent());
		hook("/ready", "{\"path\":\"other/1\",\"sourceType\":\"rtmpConn\",\"query\":\"\"}").andExpect(status().isNoContent());
		hook("/not-ready", "{\"path\":\"live/abc\"}").andExpect(status().isNoContent());
		assertThat(broadcastRepository.findOngoing(999999999L)).isEmpty();
	}

	// ---------- 소유자 종료 API ----------

	@Test
	void 소유자가_LIVE_방송을_종료하면_ENDED가_되고_송출자를_kick하며_재연결은_차단된다() throws Exception {
		Account a = newAccount();
		ready(a, "webRTCSession", "src=pc");
		Long id = ongoing(a).getId();
		when(mediaMtxClient.kickPublisher("live/" + a.channelId())).thenReturn(true);

		end(a, id).andExpect(status().isNoContent());

		Broadcast b = find(id);
		assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
		assertThat(b.getEndedAt()).isNotNull();
		assertThat(recordingRepository.findByBroadcastId(id).orElseThrow().getStatus()).isEqualTo(RecordingStatus.PROCESSING);
		verify(mediaMtxClient).kickPublisher("live/" + a.channelId());
		auth(publishBody(a, a.streamKey(), null, "src=pc")).andExpect(status().isUnauthorized());

		end(a, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BROADCAST_ALREADY_ENDED"));
	}

	@Test
	void DISCONNECTED_방송을_종료하면_kick하지_않는다() throws Exception {
		Account a = newAccount();
		ready(a, "webRTCSession", "src=pc");
		notReady(a); // 프론트 종료 순서: whip.close() → … → end
		Long id = ongoing(a).getId();

		end(a, id).andExpect(status().isNoContent());

		assertThat(find(id).getStatus()).isEqualTo(BroadcastStatus.ENDED);
		verify(mediaMtxClient, never()).kickPublisher("live/" + a.channelId());
	}

	@Test
	void kick이_실패해도_종료는_성공한다() throws Exception {
		Account a = newAccount();
		ready(a, "webRTCSession", "src=pc");
		Long id = ongoing(a).getId();
		when(mediaMtxClient.kickPublisher("live/" + a.channelId())).thenThrow(new ResourceAccessException("timeout"));

		end(a, id).andExpect(status().isNoContent());
		assertThat(find(id).getStatus()).isEqualTo(BroadcastStatus.ENDED);
	}

	@Test
	void 다른_회원은_종료할_수_없고_비로그인은_401_없는_방송은_404() throws Exception {
		Account owner = newAccount();
		Account other = newAccount();
		ready(owner, "webRTCSession", "src=pc");
		Long id = ongoing(owner).getId();

		end(other, id).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(post("/api/broadcasts/" + id + "/end")).andExpect(status().isUnauthorized());
		end(owner, 999_999_999L).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BROADCAST_NOT_FOUND"));

		assertThat(find(id).getStatus()).isEqualTo(BroadcastStatus.LIVE);
		verify(mediaMtxClient, never()).kickPublisher("live/" + owner.channelId());
	}

	@Test
	void 라이브_중_스트림_키를_재발급하면_방송이_종료되고_kick된다() throws Exception {
		Account a = newAccount();
		ready(a, "rtmpConn", "user=obs&pass=" + a.streamKey());
		Long id = ongoing(a).getId();

		mvc.perform(post("/api/channels/me/stream-key/rotate").header("Authorization", bearer(a))).andExpect(status().isOk());

		assertThat(find(id).getStatus()).isEqualTo(BroadcastStatus.ENDED);
		verify(mediaMtxClient).kickPublisher("live/" + a.channelId());
	}

	@Test
	void 진행_중_방송이_없을_때_스트림_키_재발급은_아무것도_종료하지_않는다() throws Exception {
		Account a = newAccount();
		mvc.perform(post("/api/channels/me/stream-key/rotate").header("Authorization", bearer(a))).andExpect(status().isOk());
		verify(mediaMtxClient, never()).kickPublisher("live/" + a.channelId());
		// 차단도 걸리지 않는다: 새 키로 바로 송출 가능
		String newKey = JsonPath.read(mvc.perform(get("/api/channels/me").header("Authorization", bearer(a)))
				.andReturn().getResponse().getContentAsString(), "$.streamKey");
		auth(publishBody(a, newKey, null, "src=pc")).andExpect(status().isOk());
	}

	// ---------- 인증 콜백 ----------

	@Test
	void 인증_콜백은_publish_키가_일치할_때만_200() throws Exception {
		Account a = newAccount();
		auth(publishBody(a, a.streamKey(), null, "src=pc")).andExpect(status().isOk());       // WHIP Bearer → password
		auth(publishBody(a, null, a.streamKey(), "src=obs")).andExpect(status().isOk());      // WHIP Bearer → token (버전 차이)
		auth(publishBody(a, null, null, "user=obs&pass=" + a.streamKey())).andExpect(status().isOk()); // RTMP 쿼리
		auth(publishBody(a, "", "", "user=obs&pass=" + a.streamKey())).andExpect(status().isOk()); // 빈 칸은 건너뛴다

		auth(publishBody(a, "0".repeat(32), null, "src=pc")).andExpect(status().isUnauthorized());
		auth(publishBody(a, null, null, "src=pc")).andExpect(status().isUnauthorized());
		Account other = newAccount();
		auth(publishBody(a, other.streamKey(), null, "")).andExpect(status().isUnauthorized()); // 남의 키
		auth("{\"action\":\"publish\",\"path\":\"live/999999999\",\"password\":\"" + a.streamKey() + "\",\"query\":\"\"}")
				.andExpect(status().isUnauthorized());
		auth("{\"action\":\"publish\",\"path\":\"live/" + a.channelId() + "/x\",\"password\":\"" + a.streamKey() + "\"}")
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 훅_명령을_깨뜨릴_수_있는_쿼리는_키가_맞아도_거절한다() throws Exception {
		Account a = newAccount();
		for (String q : List.of("src=pc'", "src=pc\\\"", "x=$(id)", "x=`id`", "src=pc\\\",\\\"path\\\":\\\"live/1")) {
			auth(publishBody(a, a.streamKey(), null, q)).andExpect(status().isUnauthorized());
		}
		auth(publishBody(a, a.streamKey(), null, "src=pc&t=1.5_~%20+x-y")).andExpect(status().isOk());
	}

	@Test
	void 인증_콜백은_read_playback을_허용하고_그_외_action은_404() throws Exception {
		auth("{\"action\":\"read\",\"path\":\"live/1\",\"protocol\":\"webrtc\"}").andExpect(status().isOk());
		auth("{\"action\":\"playback\",\"path\":\"live/1\"}").andExpect(status().isOk());
		auth("{\"action\":\"api\"}").andExpect(status().isNotFound());
		auth("{}").andExpect(status().isNotFound());
	}

	// ---------- 내부 토큰 ----------

	@Test
	void 훅은_X_Internal_Token이_없거나_틀리면_401이고_인증_콜백은_토큰_없이_된다() throws Exception {
		Account a = newAccount();
		String body = "{\"path\":\"live/" + a.channelId() + "\",\"sourceType\":\"rtmpConn\",\"query\":\"\"}";
		mvc.perform(post("/api/internal/mediamtx/ready").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(post("/api/internal/mediamtx/ready").header("X-Internal-Token", "wrong")
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/internal/mediamtx/not-ready").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
		assertThat(broadcastRepository.findOngoing(a.channelId())).isEmpty();

		// 인증 콜백은 MediaMTX가 헤더를 못 붙이므로 토큰 없이 동작한다(Caddy가 외부 노출을 막음)
		auth(publishBody(a, a.streamKey(), null, "")).andExpect(status().isOk());
	}

	// ---------- helpers ----------

	private void assertSource(String sourceType, String query, BroadcastSource expected) throws Exception {
		Account a = newAccount();
		ready(a, sourceType, query);
		assertThat(ongoing(a).getSource()).isEqualTo(expected);
	}

	private ResultActions ready(Account a, String sourceType, String query) throws Exception {
		return hook("/ready", "{\"path\":\"live/" + a.channelId() + "\",\"sourceType\":\"" + sourceType
				+ "\",\"query\":\"" + query + "\"}");
	}

	private ResultActions notReady(Account a) throws Exception {
		return hook("/not-ready", "{\"path\":\"live/" + a.channelId() + "\"}");
	}

	private ResultActions hook(String path, String body) throws Exception {
		return mvc.perform(post("/api/internal/mediamtx" + path).header("X-Internal-Token", props.mediamtx().internalToken())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions auth(String body) throws Exception {
		return mvc.perform(post("/api/internal/mediamtx/auth").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String publishBody(Account a, String password, String token, String query) {
		return "{\"ip\":\"127.0.0.1\",\"user\":\"\",\"password\":" + quote(password) + ",\"token\":" + quote(token)
				+ ",\"path\":\"live/" + a.channelId() + "\",\"protocol\":\"webrtc\",\"id\":null,\"action\":\"publish\",\"query\":"
				+ quote(query) + "}";
	}

	private static String quote(String s) {
		return s == null ? "null" : "\"" + s + "\"";
	}

	private ResultActions end(Account a, Long broadcastId) throws Exception {
		return mvc.perform(post("/api/broadcasts/" + broadcastId + "/end").header("Authorization", bearer(a)));
	}

	private void patchChannel(Account a, String json) throws Exception {
		mvc.perform(patch("/api/channels/me").header("Authorization", bearer(a))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
	}

	private Broadcast ongoing(Account a) {
		return broadcastRepository.findOngoing(a.channelId()).orElseThrow();
	}

	private Broadcast find(Long id) {
		return broadcastRepository.findById(id).orElseThrow();
	}

	private long countBroadcasts(Account a) {
		return tx.execute(s -> em.createQuery("select count(b) from Broadcast b where b.channel.id = :c", Long.class)
				.setParameter("c", a.channelId()).getSingleResult());
	}

	private void backdateDisconnect(Long id, long seconds) {
		tx.executeWithoutResult(s -> em.createQuery("update Broadcast b set b.disconnectedAt = :t where b.id = :id")
				.setParameter("t", Instant.now().minusSeconds(seconds)).setParameter("id", id).executeUpdate());
	}

	@SuppressWarnings("unchecked")
	private void unblock(Account a) {
		((java.util.Map<Long, Instant>) org.springframework.test.util.ReflectionTestUtils.getField(publishBlockRegistry, "blockedUntil"))
				.remove(a.channelId());
	}

	private static String bearer(Account a) {
		return "Bearer " + a.access();
	}

	private Account newAccount() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String email = "b" + suffix + "@example.com";
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"b" + suffix + "\"}"))
				.andExpect(status().isCreated());
		String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String access = JsonPath.read(res, "$.accessToken");
		String me = mvc.perform(get("/api/channels/me").header("Authorization", "Bearer " + access))
				.andReturn().getResponse().getContentAsString();
		return new Account(access, ((Number) JsonPath.read(me, "$.id")).longValue(), JsonPath.read(me, "$.streamKey"));
	}
}
