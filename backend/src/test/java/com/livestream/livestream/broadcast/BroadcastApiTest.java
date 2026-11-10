package com.livestream.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.chat.ViewerCounter;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.RecordingService;
import com.livestream.livestream.recording.RecordingStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * BC-2 통합 테스트 (DESIGN §9 BC-2): 목록 정렬 · 카테고리 필터 · 상세 · 추천 · PATCH · DELETE · 내 방송 · 채널 다시보기 + 403.
 * 시청자 수(ViewerCounter) · MediaMTX · 파일 삭제는 mock. 다른 테스트가 남긴 방송과 섞이지 않게 이 테스트의 방송에는 큰 시청자 수를 준다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastApiTest {

	private static final String PASSWORD = "password123";
	private static final long BASE_VIEWERS = 1_000_000;

	@Autowired MockMvc mvc;
	@Autowired AppProperties props;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired RecordingRepository recordingRepository;
	@Autowired EntityManager em;
	@Autowired TransactionTemplate tx;
	@MockitoBean ViewerCounter viewerCounter;
	@MockitoBean MediaMtxClient mediaMtxClient;
	@MockitoBean RecordingService recordingService;

	private final Map<Long, Long> viewers = new HashMap<>();

	record Account(String access, long channelId, long memberId) {
	}

	@BeforeEach
	void viewerCounts() {
		when(viewerCounter.count(anyLong())).thenAnswer(inv -> viewers.getOrDefault(inv.<Long>getArgument(0), 0L));
	}

	// ---------- 목록 ----------

	@Test
	void 목록은_시청자_수_내림차순_같으면_최근_시작순이고_카테고리로_거른다() throws Exception {
		Account a = newAccount(), b = newAccount(), c = newAccount(), d = newAccount();
		long ba = live(a, "GAME", BASE_VIEWERS + 10);
		long bb = live(b, "GAME", BASE_VIEWERS + 30);
		long bc = live(c, "TALK", BASE_VIEWERS + 10); // a 보다 늦게 시작
		long bd = live(d, "GAME", BASE_VIEWERS + 20);
		notReady(d); // DISCONNECTED 는 기본 목록에서 빠진다

		String all = mvc.perform(get("/api/broadcasts").param("size", "50")).andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50))
				.andReturn().getResponse().getContentAsString();
		assertThat(idsOf(all).subList(0, 3)).containsExactly(bb, bc, ba);
		assertThat(idsOf(all)).doesNotContain(bd);
		assertThat((String) JsonPath.read(all, "$.items[0].status")).isEqualTo("LIVE");
		assertThat(((Number) JsonPath.read(all, "$.items[0].viewerCount")).longValue()).isEqualTo(BASE_VIEWERS + 30);
		assertThat(((Number) JsonPath.read(all, "$.items[0].channel.id")).longValue()).isEqualTo(b.channelId());
		assertThat((String) JsonPath.read(all, "$.items[0].category.label")).isEqualTo("게임");

		String games = mvc.perform(get("/api/broadcasts").param("category", "GAME").param("size", "50"))
				.andReturn().getResponse().getContentAsString();
		assertThat(idsOf(games).subList(0, 2)).containsExactly(bb, ba);
		assertThat(idsOf(games)).doesNotContain(bc);

		String disconnected = mvc.perform(get("/api/broadcasts").param("status", "DISCONNECTED").param("size", "50"))
				.andReturn().getResponse().getContentAsString();
		assertThat(idsOf(disconnected)).contains(bd).doesNotContain(ba);

		// 페이지: 크기 1이면 첫 페이지가 가장 많이 보는 방송, 다음 페이지가 있다
		mvc.perform(get("/api/broadcasts").param("size", "1")).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(bb)).andExpect(jsonPath("$.hasNext").value(true));
		mvc.perform(get("/api/broadcasts").param("size", "1").param("page", "1"))
				.andExpect(jsonPath("$.items[0].id").value(bc));
	}

	@Test
	void 목록_입력값_검증() throws Exception {
		mvc.perform(get("/api/broadcasts").param("status", "ENDED")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/broadcasts").param("category", "COOKING")).andExpect(status().isBadRequest());
		mvc.perform(get("/api/broadcasts").param("size", "999")).andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(50));
	}

	// ---------- 상세 ----------

	@Test
	void 상세는_재생_URL_권장_모드_다시보기_소유자_여부를_준다() throws Exception {
		Account owner = newAccount(), other = newAccount();
		long id = live(owner, "MUSIC", props.grid().threshold() - 1);

		mvc.perform(get("/api/broadcasts/" + id)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.status").value("LIVE"))
				.andExpect(jsonPath("$.source").value("BROWSER_PC"))
				.andExpect(jsonPath("$.category.code").value("MUSIC"))
				.andExpect(jsonPath("$.channel.id").value(owner.channelId()))
				.andExpect(jsonPath("$.thumbnailUrl").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.endedAt").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.viewerCount").value(props.grid().threshold() - 1))
				.andExpect(jsonPath("$.recommendedMode").value("whep"))
				.andExpect(jsonPath("$.play.whepUrl").value("/live/" + owner.channelId() + "/whep"))
				.andExpect(jsonPath("$.play.hlsUrl").value("/live/" + owner.channelId() + "/index.m3u8"))
				.andExpect(jsonPath("$.donationTotal").value(0))
				.andExpect(jsonPath("$.recording.status").value("RECORDING"))
				.andExpect(jsonPath("$.recording.visibility").value("PUBLIC"))
				.andExpect(jsonPath("$.isOwner").value(false));
		mvc.perform(get("/api/broadcasts/" + id).header("Authorization", bearer(owner))).andExpect(jsonPath("$.isOwner").value(true));
		mvc.perform(get("/api/broadcasts/" + id).header("Authorization", bearer(other))).andExpect(jsonPath("$.isOwner").value(false));

		viewers.put(id, (long) props.grid().threshold()); // 기준 이상이면 grid
		mvc.perform(get("/api/broadcasts/" + id)).andExpect(jsonPath("$.recommendedMode").value("grid"));

		mvc.perform(get("/api/broadcasts/999999999")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BROADCAST_NOT_FOUND"));
	}

	// ---------- 추천 ----------

	@Test
	void 추천은_다른_채널의_같은_카테고리_라이브를_시청자_수_순으로_4개까지() throws Exception {
		Account me = newAccount();
		long base = live(me, "STUDY", BASE_VIEWERS);
		List<Long> others = new java.util.ArrayList<>();
		for (int i = 0; i < 5; i++) {
			others.add(live(newAccount(), "STUDY", BASE_VIEWERS + i));
		}
		live(newAccount(), "GAME", BASE_VIEWERS + 100); // 다른 카테고리

		String body = mvc.perform(get("/api/broadcasts/" + base + "/recommendations")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<Long> lives = ((List<Number>) JsonPath.read(body, "$.lives[*].id")).stream().map(Number::longValue).toList();
		assertThat(lives).containsExactly(others.get(4), others.get(3), others.get(2), others.get(1));
		assertThat((List<?>) JsonPath.read(body, "$.recordings")).isEmpty();
	}

	@Test
	void 같은_카테고리_라이브가_없으면_최근_공개_다시보기를_추천한다() throws Exception {
		Account me = newAccount(), past = newAccount();
		long pastId = live(past, "TALK", 0);
		end(past, pastId);
		long recordingId = markReady(pastId, "PUBLIC");
		long base = live(me, "MUSIC", 0);
		// 이 채널의 MUSIC 라이브는 남이 아니므로 추천 대상이 아니다. 다른 채널 MUSIC 라이브가 남아 있을 수 있어 카테고리를 하나 비운다.
		endAllLive("MUSIC", me.channelId());

		String body = mvc.perform(get("/api/broadcasts/" + base + "/recommendations")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat((List<?>) JsonPath.read(body, "$.lives")).isEmpty();
		assertThat(((Number) JsonPath.read(body, "$.recordings[0].id")).longValue()).isEqualTo(recordingId);
		assertThat(((Number) JsonPath.read(body, "$.recordings[0].broadcastId")).longValue()).isEqualTo(pastId);
		assertThat(((Number) JsonPath.read(body, "$.recordings[0].channel.id")).longValue()).isEqualTo(past.channelId());
		assertThat((List<?>) JsonPath.read(body, "$.recordings")).hasSizeLessThanOrEqualTo(4);
	}

	// ---------- PATCH ----------

	@Test
	void 진행_중_방송을_고치면_채널_방송_정보도_바뀐다() throws Exception {
		Account a = newAccount();
		long id = live(a, "GAME", 0);

		patchBroadcast(a, id, "{\"title\":\"  바뀐 제목  \",\"category\":\"TALK\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("바뀐 제목")).andExpect(jsonPath("$.category.code").value("TALK"))
				.andExpect(jsonPath("$.isOwner").value(true));
		mvc.perform(get("/api/channels/me").header("Authorization", bearer(a)))
				.andExpect(jsonPath("$.title").value("바뀐 제목")).andExpect(jsonPath("$.category.code").value("TALK"));

		notReady(a); // DISCONNECTED 도 진행 중
		patchBroadcast(a, id, "{\"category\":\"MUSIC\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("바뀐 제목")).andExpect(jsonPath("$.category.code").value("MUSIC"));
		mvc.perform(get("/api/channels/me").header("Authorization", bearer(a))).andExpect(jsonPath("$.category.code").value("MUSIC"));
	}

	@Test
	void 지난_방송은_제목만_고치고_카테고리는_409() throws Exception {
		Account a = newAccount();
		long id = live(a, "GAME", 0);
		end(a, id);
		patchChannel(a, "{\"title\":\"다음 방송\"}");

		patchBroadcast(a, id, "{\"title\":\"지난 방송 제목\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("지난 방송 제목")).andExpect(jsonPath("$.status").value("ENDED"));
		mvc.perform(get("/api/channels/me").header("Authorization", bearer(a))).andExpect(jsonPath("$.title").value("다음 방송"));

		patchBroadcast(a, id, "{\"title\":\"x\",\"category\":\"TALK\"}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("BROADCAST_NOT_LIVE"));
		mvc.perform(get("/api/broadcasts/" + id)).andExpect(jsonPath("$.title").value("지난 방송 제목"))
				.andExpect(jsonPath("$.category.code").value("GAME"));
	}

	@Test
	void 방송_수정_검증과_권한() throws Exception {
		Account owner = newAccount(), other = newAccount();
		long id = live(owner, "GAME", 0);

		patchBroadcast(other, id, "{\"title\":\"남의 방송\"}").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(patch("/api/broadcasts/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
				.andExpect(status().isUnauthorized());
		patchBroadcast(owner, id, "{\"title\":\"   \"}").andExpect(status().isBadRequest());
		patchBroadcast(owner, id, "{\"title\":\"" + "a".repeat(101) + "\"}").andExpect(status().isBadRequest());
		patchBroadcast(owner, id, "{\"title\":\"a\\nb\"}").andExpect(status().isBadRequest());
		patchBroadcast(owner, id, "{\"category\":\"COOKING\"}").andExpect(status().isBadRequest());
		patchBroadcast(owner, 999_999_999L, "{\"title\":\"x\"}").andExpect(status().isNotFound());
		mvc.perform(get("/api/broadcasts/" + id)).andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.not("남의 방송")));
	}

	// ---------- DELETE ----------

	@Test
	void 진행_중_방송을_삭제하면_종료_kick_후_숨겨지고_녹화는_FILE_DELETED_파일_삭제가_호출된다() throws Exception {
		Account a = newAccount();
		long id = live(a, "GAME", 0);
		when(mediaMtxClient.kickPublisher(anyString())).thenReturn(true);

		mvc.perform(delete("/api/broadcasts/" + id).header("Authorization", bearer(a))).andExpect(status().isNoContent());

		Broadcast b = broadcastRepository.findById(id).orElseThrow();
		assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
		assertThat(b.getDeletedAt()).isNotNull();
		var recording = recordingRepository.findByBroadcastId(id).orElseThrow();
		assertThat(recording.getStatus()).isEqualTo(RecordingStatus.FILE_DELETED);
		assertThat(recording.getFileDeletedAt()).isNotNull();
		verify(mediaMtxClient).kickPublisher("live/" + a.channelId());
		verify(recordingService).deleteFiles(null, "thumbnails/" + id + ".jpg");

		mvc.perform(get("/api/broadcasts/" + id)).andExpect(status().isNotFound());
		mvc.perform(get("/api/broadcasts/" + id + "/recommendations")).andExpect(status().isNotFound());
		mvc.perform(delete("/api/broadcasts/" + id).header("Authorization", bearer(a))).andExpect(status().isNotFound());
		mvc.perform(get("/api/channels/me").header("Authorization", bearer(a)))
				.andExpect(jsonPath("$.currentBroadcast").value(org.hamcrest.Matchers.nullValue()));
		assertThat(idsOf(mvc.perform(get("/api/members/me/broadcasts").header("Authorization", bearer(a)))
				.andReturn().getResponse().getContentAsString())).doesNotContain(id);
	}

	@Test
	void 지난_방송_삭제는_kick하지_않고_다른_회원은_403() throws Exception {
		Account owner = newAccount(), other = newAccount();
		long id = live(owner, "GAME", 0);
		end(owner, id);
		org.mockito.Mockito.clearInvocations(mediaMtxClient);

		mvc.perform(delete("/api/broadcasts/" + id).header("Authorization", bearer(other))).andExpect(status().isForbidden());
		mvc.perform(delete("/api/broadcasts/" + id)).andExpect(status().isUnauthorized());
		assertThat(broadcastRepository.findById(id).orElseThrow().getDeletedAt()).isNull();
		verify(recordingService, never()).deleteFiles(any(), any());

		mvc.perform(delete("/api/broadcasts/" + id).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
		verify(mediaMtxClient, never()).kickPublisher(anyString());
		verify(recordingService).deleteFiles(null, "thumbnails/" + id + ".jpg");
	}

	@Test
	void READY_다시보기가_있는_방송을_삭제하면_vod_경로와_파일_삭제_시각이_남는다() throws Exception {
		Account a = newAccount();
		long id = live(a, "GAME", 0);
		end(a, id);
		markReady(id, "PUBLIC");
		String vod = recordingRepository.findByBroadcastId(id).orElseThrow().getFilePath();
		assertThat(vod).startsWith("vod/");

		mvc.perform(delete("/api/broadcasts/" + id).header("Authorization", bearer(a))).andExpect(status().isNoContent());

		verify(recordingService).deleteFiles(vod, "thumbnails/" + id + ".jpg");
		var recording = recordingRepository.findByBroadcastId(id).orElseThrow();
		assertThat(recording.getStatus()).isEqualTo(RecordingStatus.FILE_DELETED);
		assertThat(recording.getFileDeletedAt()).isNotNull();
		// 채널의 공개 다시보기 · 추천에서도 사라진다
		mvc.perform(get("/api/channels/" + a.channelId() + "/broadcasts")).andExpect(jsonPath("$.totalElements").value(0));
	}

	// ---------- 내 방송 · 채널 다시보기 ----------

	@Test
	void 내_방송_기록은_최신순이고_다시보기_상태를_담으며_남의_방송은_없다() throws Exception {
		Account a = newAccount(), other = newAccount();
		long first = live(a, "GAME", 0);
		end(a, first);
		unblock(a);
		long second = live(a, "TALK", 0);
		live(other, "GAME", 0);

		String body = mvc.perform(get("/api/members/me/broadcasts").header("Authorization", bearer(a)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
				.andReturn().getResponse().getContentAsString();
		assertThat(idsOf(body)).containsExactly(second, first);
		assertThat((String) JsonPath.read(body, "$.items[0].recording.status")).isEqualTo("RECORDING");
		assertThat((String) JsonPath.read(body, "$.items[1].recording.status")).isEqualTo("PROCESSING");
		assertThat((String) JsonPath.read(body, "$.items[1].status")).isEqualTo("ENDED");
		assertThat((Object) JsonPath.read(body, "$.items[1].endedAt")).isNotNull();
		mvc.perform(get("/api/members/me/broadcasts")).andExpect(status().isUnauthorized());
	}

	@Test
	void 채널_다시보기는_종료되고_READY_PUBLIC인_것만_최신순() throws Exception {
		Account a = newAccount();
		long older = endedWithRecording(a, "PUBLIC", true);
		long privateOne = endedWithRecording(a, "PRIVATE", true);
		long notReady = endedWithRecording(a, "PUBLIC", false);
		long newer = endedWithRecording(a, "PUBLIC", true);

		String body = mvc.perform(get("/api/channels/" + a.channelId() + "/broadcasts")).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andReturn().getResponse().getContentAsString();
		List<Long> broadcastIds = ((List<Number>) JsonPath.read(body, "$.items[*].broadcastId")).stream().map(Number::longValue).toList();
		assertThat(broadcastIds).containsExactly(newer, older).doesNotContain(privateOne, notReady);
		assertThat((Integer) JsonPath.read(body, "$.items[0].commentCount")).isZero();

		mvc.perform(get("/api/channels/999999999/broadcasts")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
	}

	// ---------- helpers ----------

	/** ready 훅으로 LIVE 방송을 만들고 시청자 수를 정한다. */
	private long live(Account a, String category, long viewerCount) throws Exception {
		patchChannel(a, "{\"category\":\"" + category + "\"}");
		hook("/ready", "{\"path\":\"live/" + a.channelId() + "\",\"sourceType\":\"webRTCSession\",\"query\":\"src=pc\"}")
				.andExpect(status().isNoContent());
		long id = broadcastRepository.findOngoing(a.channelId()).orElseThrow().getId();
		viewers.put(id, viewerCount);
		return id;
	}

	private void notReady(Account a) throws Exception {
		hook("/not-ready", "{\"path\":\"live/" + a.channelId() + "\"}").andExpect(status().isNoContent());
	}

	private void end(Account a, long id) throws Exception {
		mvc.perform(post("/api/broadcasts/" + id + "/end").header("Authorization", bearer(a))).andExpect(status().isNoContent());
	}

	/** 종료 뒤 차단(30초)을 풀어 같은 채널로 다음 방송을 시작할 수 있게 한다. */
	@SuppressWarnings("unchecked")
	private void unblock(Account a) {
		((Map<Long, Instant>) org.springframework.test.util.ReflectionTestUtils.getField(publishBlockRegistry, "blockedUntil"))
				.remove(a.channelId());
	}

	@Autowired PublishBlockRegistry publishBlockRegistry;

	private long endedWithRecording(Account a, String visibility, boolean ready) throws Exception {
		long id = live(a, "GAME", 0);
		end(a, id);
		unblock(a);
		if (ready) {
			markReady(id, visibility);
		} else {
			tx.executeWithoutResult(s -> em.createQuery("update Recording r set r.visibility = :v where r.broadcast.id = :id")
					.setParameter("v", com.livestream.livestream.recording.Visibility.valueOf(visibility))
					.setParameter("id", id).executeUpdate());
		}
		return id;
	}

	/** REC-1 병합 완료를 흉내 낸다. */
	private long markReady(long broadcastId, String visibility) {
		tx.executeWithoutResult(s -> em.createQuery("""
						update Recording r set r.status = :st, r.visibility = :v, r.filePath = :f, r.durationSeconds = 60
						where r.broadcast.id = :id""")
				.setParameter("st", RecordingStatus.READY)
				.setParameter("v", com.livestream.livestream.recording.Visibility.valueOf(visibility))
				.setParameter("f", "vod/" + UUID.randomUUID() + ".mp4")
				.setParameter("id", broadcastId).executeUpdate());
		return recordingRepository.findByBroadcastId(broadcastId).orElseThrow().getId();
	}

	/** 다른 테스트가 남긴 해당 카테고리 LIVE 방송을 끝낸 것으로 바꾼다(추천 빈 경우 확인용). */
	private void endAllLive(String category, long exceptChannelId) {
		tx.executeWithoutResult(s -> em.createQuery("""
						update Broadcast b set b.status = :ended, b.endedAt = :now
						where b.status = :live and b.category = :c and b.channel.id <> :except""")
				.setParameter("ended", BroadcastStatus.ENDED).setParameter("now", Instant.now())
				.setParameter("live", BroadcastStatus.LIVE)
				.setParameter("c", com.livestream.livestream.channel.Category.valueOf(category))
				.setParameter("except", exceptChannelId).executeUpdate());
	}

	private ResultActions hook(String path, String body) throws Exception {
		return mvc.perform(post("/api/internal/mediamtx" + path).header("X-Internal-Token", props.mediamtx().internalToken())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions patchBroadcast(Account a, long id, String json) throws Exception {
		return mvc.perform(patch("/api/broadcasts/" + id).header("Authorization", bearer(a))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private void patchChannel(Account a, String json) throws Exception {
		mvc.perform(patch("/api/channels/me").header("Authorization", bearer(a))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
	}

	private static List<Long> idsOf(String pageJson) {
		return ((List<Number>) JsonPath.read(pageJson, "$.items[*].id")).stream().map(Number::longValue).toList();
	}

	private static String bearer(Account a) {
		return "Bearer " + a.access();
	}

	private Account newAccount() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String email = "c" + suffix + "@example.com";
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"c" + suffix + "\"}"))
				.andExpect(status().isCreated());
		String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new Account(JsonPath.read(res, "$.accessToken"), ((Number) JsonPath.read(res, "$.member.channelId")).longValue(),
				((Number) JsonPath.read(res, "$.member.id")).longValue());
	}
}
