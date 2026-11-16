package com.livestream.livestream.clip;

import static com.livestream.livestream.clip.ClipFixtures.awaitDraft;
import static com.livestream.livestream.clip.ClipFixtures.awaitProcessed;
import static com.livestream.livestream.clip.ClipFixtures.createDraft;
import static com.livestream.livestream.clip.ClipFixtures.createDraftClip;
import static com.livestream.livestream.clip.ClipFixtures.readyDraft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.mediamtx.MediaMtxClient.RecordingSegment;
import com.livestream.livestream.recording.RecordingMergeService;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import com.livestream.livestream.thumbnail.FfmpegRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClientException;

/**
 * CLIP-3 (DESIGN §4.18 라이브 클립 초안 · §4.16 clip-drafts · §9 CLIP-3 완료(BE) ①~⑥): 초안 생성 조건 · 받기(가짜 재생 서버 ·
 * ffprobe) 뒤 READY · 토큰 재생 · 초안에서 클립 · 보관 정리 · 방송 삭제 연동. ⑦(기존 라이브 클립)은 ClipApiTest ④.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClipDraftApiTest {

	@TempDir static Path recordingsDir;
	@TempDir static Path uploadsDir;

	@DynamicPropertySource
	static void dirs(DynamicPropertyRegistry registry) {
		registry.add("app.recording.dir", () -> recordingsDir.toString());
		registry.add("app.uploads.dir", () -> uploadsDir.toString());
	}

	@Autowired MockMvc mvc;
	@Autowired BroadcastLifecycleService lifecycle;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired ClipDraftRepository draftRepository;
	@Autowired ClipDraftService draftService;
	@Autowired ClipDraftVideoTokenService draftTokens;
	@Autowired ClipProcessingService processing;
	@Autowired RecordingMergeService mergeService;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean MediaMtxClient mediaMtxClient;
	@MockitoBean FfmpegRunner ffmpeg;

	private final ClipFixtures.Knobs knobs = new ClipFixtures.Knobs();

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(mergeService, "mergeDelay", Duration.ofHours(1));
		ClipFixtures.stub(ffmpeg, mediaMtxClient, knobs);
	}

	// ① LIVE 에서 초안 202 → 받기 뒤 READY, 토큰 주소, 창 길이는 ffprobe 실제 길이
	@Test
	void 초안은_202_뒤_최근_2분을_받아_READY가_되고_창_길이는_실제_길이다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long broadcastId = liveBroadcast(streamer, 300);
		Instant startedAt = broadcastRepository.findById(broadcastId).orElseThrow().getStartedAt();

		String created = mvc.perform(post("/api/broadcasts/" + broadcastId + "/clip-drafts").header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.broadcastId").value((int) broadcastId))
				.andExpect(jsonPath("$.windowSeconds").value(120))
				.andExpect(jsonPath("$.videoUrl").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		long draftId = ((Number) JsonPath.read(created, "$.id")).longValue();
		int windowStart = JsonPath.read(created, "$.windowStartSeconds");
		assertThat(windowStart).isBetween(179, 182); // 300 − 120, 초 내림 · 실행 시간 여유

		String done = awaitDraft(mvc, viewer, draftId);
		assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("READY");
		assertThat((Integer) JsonPath.read(done, "$.windowSeconds")).isEqualTo(12); // 가짜 ffprobe 12.4 → 내림
		assertThat((Integer) JsonPath.read(done, "$.windowStartSeconds")).isEqualTo(windowStart);
		assertThat((String) JsonPath.read(done, "$.videoUrl")).startsWith("/api/clip-drafts/" + draftId + "/video?token=");
		String file = draftRepository.findById(draftId).orElseThrow().getFilePath();
		assertThat(file).startsWith("private/clip-drafts/");
		assertThat(recordingsDir.resolve(file)).isRegularFile();

		ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
		verify(mediaMtxClient).downloadRecording(eq("live/" + streamer.channelId()), start.capture(), eq(Duration.ofSeconds(120)), any());
		assertThat(start.getValue()).isEqualTo(startedAt.plusSeconds(windowStart));
	}

	// ② 생성 조건: 시작 직후 400, DISCONNECTED · ENDED 409, PENDING 중복 409, 처리 중 클립 409, 새 초안이 이전 행 · 파일을 지움
	@Test
	void 초안_생성_조건과_회원당_하나() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		lifecycle.onReady(streamer.channelId(), BroadcastSource.BROWSER_PC);
		long broadcastId = broadcastRepository.findOngoing(streamer.channelId()).orElseThrow().getId();

		draft(viewer, broadcastId).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value(ClipDraftService.TOO_EARLY));
		mvc.perform(post("/api/broadcasts/" + broadcastId + "/clip-drafts")).andExpect(status().isUnauthorized());
		draft(viewer, 999_999_999L).andExpect(status().isNotFound());

		// 시작 30초 뒤: 창은 방송 시작부터(짧은 창)
		jdbc.update("update broadcast set started_at = started_at - interval 30 second where id = ?", broadcastId);
		knobs.holdDownload = new CountDownLatch(1);
		String pending = draft(viewer, broadcastId).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.windowStartSeconds").value(0))
				.andReturn().getResponse().getContentAsString();
		assertThat((Integer) JsonPath.read(pending, "$.windowSeconds")).isBetween(30, 32);
		long first = ((Number) JsonPath.read(pending, "$.id")).longValue();
		// 받는 중이면 새 초안 409, 그 초안에서 클립 409
		draft(viewer, broadcastId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIP_IN_PROGRESS"));
		draftClip(viewer, first, 0, 5).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIP_DRAFT_NOT_READY"));
		knobs.holdDownload.countDown();
		knobs.holdDownload = null;
		awaitDraft(mvc, viewer, first);
		Path firstFile = recordingsDir.resolve(draftRepository.findById(first).orElseThrow().getFilePath());
		assertThat(firstFile).isRegularFile();

		// 새 초안을 만들면 이전 READY 초안의 행 · 파일을 지운다(회원당 1개)
		long second = readyDraft(mvc, viewer, broadcastId);
		assertThat(draftRepository.findById(first)).isEmpty();
		assertThat(firstFile).doesNotExist();
		mvc.perform(get("/api/clip-drafts/" + first).header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLIP_DRAFT_NOT_FOUND"));

		// 처리 중인 내 클립이 있으면 새 초안 409
		knobs.holdCut = new CountDownLatch(1);
		long clipId = createDraftClip(mvc, viewer, second, "{\"startSeconds\":0,\"durationSeconds\":5}");
		draft(viewer, broadcastId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIP_IN_PROGRESS"));
		knobs.holdCut.countDown();
		knobs.holdCut = null;
		awaitProcessed(mvc, viewer, clipId);

		// 재생 서버에 그 구간이 없으면 FAILED(NO_MEDIA), FAILED 초안도 새 초안이 대체한다
		Path secondFile = recordingsDir.resolve(draftRepository.findById(second).orElseThrow().getFilePath());
		knobs.liveHasMedia = false;
		long noMedia = createDraft(mvc, viewer, broadcastId);
		// READY 클립이 가리키던 이전 초안도 대체된다: 클립의 draft_id 는 null(FK SET NULL), 초안 파일은 지워진다
		assertThat(draftRepository.findById(second)).isEmpty();
		assertThat(secondFile).doesNotExist();
		assertThat(jdbc.queryForObject("select draft_id from clip where id = ?", Long.class, clipId)).isNull();
		assertThat((String) JsonPath.read(awaitDraft(mvc, viewer, noMedia), "$.status")).isEqualTo("FAILED");
		knobs.liveHasMedia = true;
		readyDraft(mvc, viewer, broadcastId);
		assertThat(draftRepository.findById(noMedia)).isEmpty();

		lifecycle.onNotReady(streamer.channelId()); // DISCONNECTED
		draft(viewer, broadcastId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BROADCAST_NOT_LIVE"));
		lifecycle.endOngoingOf(streamer.channelId()); // ENDED
		draft(viewer, broadcastId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BROADCAST_NOT_LIVE"));
	}

	// 동시 요청: 서로 다른 회원의 첫 초안은 모두 202(갭 잠금 데드락 없음), 같은 회원의 동시 요청은 하나만 202 · 나머지 409
	@Test
	void 동시에_만든_첫_초안은_서로_막지_않고_같은_회원은_하나만_된다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		long broadcastId = liveBroadcast(streamer, 300);
		knobs.holdDownload = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			for (int round = 0; round < 3; round++) {
				List<Account> viewers = new ArrayList<>();
				for (int i = 0; i < 4; i++) {
					viewers.add(TestAccounts.create(mvc));
				}
				assertThat(concurrently(pool, viewers.stream().map(v -> (Callable<Integer>) () -> draftStatus(v, broadcastId)).toList()))
						.containsOnly(202);
			}
			Account same = TestAccounts.create(mvc);
			List<Callable<Integer>> twice = List.of(() -> draftStatus(same, broadcastId), () -> draftStatus(same, broadcastId));
			assertThat(concurrently(pool, twice)).containsExactlyInAnyOrder(202, 409);
		} finally {
			knobs.holdDownload.countDown();
			knobs.holdDownload = null;
			pool.shutdownNow();
		}
	}

	// 받은 길이가 최소 클립 길이(5초)보다 짧으면 쓸 수 있는 구간이 없으므로 FAILED(NO_MEDIA)
	@Test
	void 받은_길이가_최소_클립_길이보다_짧으면_NO_MEDIA() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		when(ffmpeg.ffprobe(any(), any())).thenReturn(new FfmpegRunner.Result(0, "4.6\n", false));
		long draftId = createDraft(mvc, viewer, liveBroadcast(streamer, 300));
		assertThat((String) JsonPath.read(awaitDraft(mvc, viewer, draftId), "$.status")).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select failure_reason from clip_draft where id = ?", String.class, draftId)).isEqualTo("NO_MEDIA");
		assertThat(jdbc.queryForObject("select file_path from clip_draft where id = ?", String.class, draftId)).isNull();
	}

	// CLIP-3#1: 송출 직후 드리프트 리셋으로 짧은 첫 조각이 겹치면 창 시작을 본 조각 시작(초 올림)으로 옮기고 READY 때 갱신
	@Test
	void 첫_조각이_짧으면_창을_본_조각_시작으로_옮기고_windowStartSeconds를_갱신한다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long broadcastId = liveBroadcast(streamer, 30);
		Instant startedAt = broadcastRepository.findById(broadcastId).orElseThrow().getStartedAt();
		when(mediaMtxClient.listSegments(any(), any(), any())).thenAnswer(inv -> {
			Instant start = inv.getArgument(1);
			return List.of(new RecordingSegment(start.minusMillis(60), 5), new RecordingSegment(start.plusMillis(1990), 300));
		});

		String created = draft(viewer, broadcastId).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.windowStartSeconds").value(0))
				.andReturn().getResponse().getContentAsString();
		long draftId = ((Number) JsonPath.read(created, "$.id")).longValue();
		int requested = JsonPath.read(created, "$.windowSeconds");

		String done = awaitDraft(mvc, viewer, draftId);
		assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("READY");
		assertThat((Integer) JsonPath.read(done, "$.windowStartSeconds")).isEqualTo(2); // 1.99초 → 2
		assertThat((Integer) JsonPath.read(done, "$.windowSeconds")).isEqualTo(12); // 가짜 ffprobe 12.4 → 내림
		verify(mediaMtxClient).listSegments(eq("live/" + streamer.channelId()), eq(startedAt), eq(startedAt.plusSeconds(requested)));
		verify(mediaMtxClient).downloadRecording(eq("live/" + streamer.channelId()), eq(startedAt.plusSeconds(2)),
				eq(Duration.ofSeconds(requested - 2)), any());

		// 클립 시작(방송 기준) = 옮긴 windowStart + 초안 기준 시작
		long clipId = createDraftClip(mvc, viewer, draftId, "{\"startSeconds\":3,\"durationSeconds\":5}");
		assertThat(jdbc.queryForObject("select start_seconds from clip where id = ?", Integer.class, clipId)).isEqualTo(5);
		awaitProcessed(mvc, viewer, clipId);
	}

	// /list 가 실패하면 창을 옮기지 않고 그대로 받는다
	@Test
	void 조각_목록_조회가_실패하면_창을_그대로_받는다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long broadcastId = liveBroadcast(streamer, 30);
		Instant startedAt = broadcastRepository.findById(broadcastId).orElseThrow().getStartedAt();
		when(mediaMtxClient.listSegments(any(), any(), any())).thenThrow(new RestClientException("연결 실패"));

		long draftId = createDraft(mvc, viewer, broadcastId);
		String done = awaitDraft(mvc, viewer, draftId);
		assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("READY");
		assertThat((Integer) JsonPath.read(done, "$.windowStartSeconds")).isEqualTo(0);
		verify(mediaMtxClient).downloadRecording(eq("live/" + streamer.channelId()), eq(startedAt), any(), any());
	}

	// 옮긴 창이 min-seconds(5)보다 짧으면 받지 않고 FAILED(NO_MEDIA)
	@Test
	void 옮긴_창이_최소_길이보다_짧으면_받지_않고_NO_MEDIA() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		when(mediaMtxClient.listSegments(any(), any(), any())).thenAnswer(inv -> {
			Instant start = inv.getArgument(1);
			Instant end = inv.getArgument(2);
			return List.of(new RecordingSegment(start, 3), new RecordingSegment(end.minusSeconds(3), 100));
		});

		long draftId = createDraft(mvc, viewer, liveBroadcast(streamer, 30));
		assertThat((String) JsonPath.read(awaitDraft(mvc, viewer, draftId), "$.status")).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select failure_reason from clip_draft where id = ?", String.class, draftId)).isEqualTo("NO_MEDIA");
		verify(mediaMtxClient, never()).downloadRecording(any(), any(), any(), any());
	}

	// 받는 중에 초안이 지워지면(방송 삭제) 처리기는 받은 파일을 버린다(고아 파일 없음)
	@Test
	void 받는_중에_초안이_지워지면_받은_파일을_버린다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long broadcastId = liveBroadcast(streamer, 300);
		knobs.holdDownload = new CountDownLatch(1);
		long draftId;
		try {
			draftId = createDraft(mvc, viewer, broadcastId);
			mvc.perform(delete("/api/broadcasts/" + broadcastId).header(HttpHeaders.AUTHORIZATION, streamer.bearer()))
					.andExpect(status().isNoContent());
			assertThat(draftRepository.findById(draftId)).isEmpty();
		} finally {
			knobs.holdDownload.countDown();
			knobs.holdDownload = null;
		}
		Set<?> inFlight = (Set<?>) ReflectionTestUtils.getField(processing, "draftsInFlight");
		Instant deadline = Instant.now().plusSeconds(10);
		while (inFlight.contains(draftId) && Instant.now().isBefore(deadline)) {
			Thread.sleep(50);
		}
		assertThat(inFlight.contains(draftId)).isFalse();
		Set<String> kept = new HashSet<>(jdbc.queryForList("select file_path from clip_draft where file_path is not null", String.class));
		Path dir = recordingsDir.resolve("private/clip-drafts");
		if (Files.isDirectory(dir)) {
			try (Stream<Path> listed = Files.list(dir)) {
				assertThat(listed.map(f -> "private/clip-drafts/" + f.getFileName()).toList()).allMatch(kept::contains);
			}
		}
	}

	// ③ 만든 회원만: 다른 회원 404, 토큰 재생 200 · Range 206, 토큰 없음 · 다름 401, READY 아님 409
	@Test
	void 초안은_만든_회원만_보고_토큰으로_재생한다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long broadcastId = liveBroadcast(streamer, 300);
		long draftId = readyDraft(mvc, viewer, broadcastId);

		mvc.perform(get("/api/clip-drafts/" + draftId).header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLIP_DRAFT_NOT_FOUND"));
		mvc.perform(get("/api/clip-drafts/" + draftId).header(HttpHeaders.AUTHORIZATION, streamer.bearer()))
				.andExpect(status().isNotFound()); // 채널 주인도 남의 초안은 못 본다
		mvc.perform(get("/api/clip-drafts/" + draftId)).andExpect(status().isUnauthorized());
		draftClip(other, draftId, 0, 5).andExpect(status().isNotFound());

		String videoUrl = JsonPath.read(mvc.perform(get("/api/clip-drafts/" + draftId).header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.videoUrl");
		mvc.perform(get(videoUrl))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"));
		mvc.perform(get(videoUrl).header(HttpHeaders.RANGE, "bytes=0-99"))
				.andExpect(status().isPartialContent())
				.andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-99/" + ClipFixtures.VIDEO_SIZE));

		mvc.perform(get("/api/clip-drafts/" + draftId + "/video")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/clip-drafts/" + draftId + "/video").param("token", viewer.access())).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/clip-drafts/" + (draftId + 1000) + "/video").param("token", draftTokens.issue(draftId)))
				.andExpect(status().isUnauthorized()); // 다른 초안의 토큰

		// READY 가 아닌 초안(받는 중)은 토큰이 맞아도 409
		Account streamer2 = TestAccounts.create(mvc);
		long broadcast2 = liveBroadcast(streamer2, 300);
		knobs.holdDownload = new CountDownLatch(1);
		long pending = createDraft(mvc, other, broadcast2);
		try {
			mvc.perform(get("/api/clip-drafts/" + pending + "/video").param("token", draftTokens.issue(pending)))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIP_DRAFT_NOT_READY"));
		} finally {
			knobs.holdDownload.countDown();
			knobs.holdDownload = null;
		}
		awaitDraft(mvc, other, pending);
	}

	// ④ 초안에서 클립: 202 → READY, start_seconds = windowStart + start, 범위 · 길이 400, 방송이 끝나도 202
	@Test
	void 초안에서_클립을_만들면_방송_기준_시작으로_저장되고_방송이_끝나도_된다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long broadcastId = liveBroadcast(streamer, 300);
		long draftId = readyDraft(mvc, viewer, broadcastId);
		ClipDraft draft = draftRepository.findById(draftId).orElseThrow();
		int windowStart = draft.getWindowStartSeconds();
		assertThat(draft.getWindowSeconds()).isEqualTo(12);

		draftClip(viewer, draftId, 8, 5).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(ClipService.OUT_OF_RANGE)); // 8 + 5 > 12
		draftClip(viewer, draftId, -1, 5).andExpect(status().isBadRequest());
		draftClip(viewer, draftId, 0, 4).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("클립 길이는 5~60초입니다"));
		draftClip(viewer, draftId, 0, 61).andExpect(status().isBadRequest());
		draftClip(viewer, 999_999_999L, 0, 5).andExpect(status().isNotFound());

		String created = mvc.perform(post("/api/clip-drafts/" + draftId + "/clips").header(HttpHeaders.AUTHORIZATION, viewer.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"명장면\",\"startSeconds\":7,\"durationSeconds\":5,\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.source").value("LIVE"))
				.andExpect(jsonPath("$.title").value("명장면"))
				.andExpect(jsonPath("$.startSeconds").value(windowStart + 7))
				.andReturn().getResponse().getContentAsString();
		long clipId = ((Number) JsonPath.read(created, "$.id")).longValue();
		String done = awaitProcessed(mvc, viewer, clipId);
		assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("READY");
		assertThat((String) JsonPath.read(done, "$.visibility")).isEqualTo("PRIVATE");
		assertThat(jdbc.queryForObject("select start_seconds from clip where id = ?", Integer.class, clipId)).isEqualTo(windowStart + 7);
		assertThat(jdbc.queryForObject("select draft_id from clip where id = ?", Long.class, clipId)).isEqualTo(draftId);

		// 방송이 끝나도 초안에서 만들 수 있다(초안 하나에서 여러 개)
		lifecycle.endOngoingOf(streamer.channelId());
		long afterEnd = createDraftClip(mvc, viewer, draftId, "{\"startSeconds\":0,\"durationSeconds\":12}");
		assertThat((String) JsonPath.read(awaitProcessed(mvc, viewer, afterEnd), "$.status")).isEqualTo("READY");
	}

	// ⑤ 보관 정리: 오래된 초안 삭제(파일 포함), 처리 중 클립이 가리키는 초안은 남김, READY 클립의 draft_id 는 null
	@Test
	void 보관_시간이_지난_초안은_지우되_처리_중_클립이_가리키는_것은_남긴다() throws Exception {
		Account streamerA = TestAccounts.create(mvc);
		Account streamerB = TestAccounts.create(mvc);
		Account viewerA = TestAccounts.create(mvc);
		Account viewerB = TestAccounts.create(mvc);
		long draftA = readyDraft(mvc, viewerA, liveBroadcast(streamerA, 300));
		long draftB = readyDraft(mvc, viewerB, liveBroadcast(streamerB, 300));
		Path fileA = recordingsDir.resolve(draftRepository.findById(draftA).orElseThrow().getFilePath());
		Path fileB = recordingsDir.resolve(draftRepository.findById(draftB).orElseThrow().getFilePath());
		long readyClip = createDraftClip(mvc, viewerA, draftA, "{\"startSeconds\":0,\"durationSeconds\":5}");
		awaitProcessed(mvc, viewerA, readyClip);

		// 보관 시간 안이면 그대로
		draftService.purgeExpired(Instant.now());
		assertThat(draftRepository.findById(draftA)).isPresent();

		knobs.holdCut = new CountDownLatch(1);
		long heldClip = createDraftClip(mvc, viewerB, draftB, "{\"startSeconds\":0,\"durationSeconds\":5}");
		try {
			draftService.purgeExpired(Instant.now().plus(Duration.ofMinutes(11)));
			assertThat(draftRepository.findById(draftA)).isEmpty();
			assertThat(fileA).doesNotExist();
			assertThat(jdbc.queryForObject("select draft_id from clip where id = ?", Long.class, readyClip)).isNull();
			mvc.perform(get("/api/clips/" + readyClip)).andExpect(status().isOk()); // 클립은 독립 파일
			// 처리 중 클립이 가리키는 초안은 남는다
			assertThat(draftRepository.findById(draftB)).isPresent();
			assertThat(fileB).isRegularFile();
		} finally {
			knobs.holdCut.countDown();
			knobs.holdCut = null;
		}
		assertThat((String) JsonPath.read(awaitProcessed(mvc, viewerB, heldClip), "$.status")).isEqualTo("READY");
		draftService.purgeExpired(Instant.now().plus(Duration.ofMinutes(11)));
		assertThat(draftRepository.findById(draftB)).isEmpty();
		assertThat(fileB).doesNotExist();
	}

	// 갇힌 초안: 처리기 집합에 없는 오래된 PENDING 은 failStale 뒤 FAILED(TIMEOUT)
	@Test
	void 처리기_집합에_없는_오래된_PENDING_초안은_failStale_뒤_TIMEOUT() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long draftId = readyDraft(mvc, viewer, liveBroadcast(streamer, 300));
		jdbc.update("update clip_draft set status = 'PENDING', created_at = created_at - interval 10 minute where id = ?", draftId);
		processing.failStale(Instant.now());
		mvc.perform(get("/api/clip-drafts/" + draftId).header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
				.andExpect(jsonPath("$.status").value("FAILED"));
		assertThat(jdbc.queryForObject("select failure_reason from clip_draft where id = ?", String.class, draftId)).isEqualTo("TIMEOUT");
	}

	// ⑥ 방송 삭제 시 초안 삭제
	@Test
	void 방송을_삭제하면_그_방송의_초안도_지워진다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long broadcastId = liveBroadcast(streamer, 300);
		long draftId = readyDraft(mvc, viewer, broadcastId);
		Path file = recordingsDir.resolve(draftRepository.findById(draftId).orElseThrow().getFilePath());

		mvc.perform(delete("/api/broadcasts/" + broadcastId).header(HttpHeaders.AUTHORIZATION, streamer.bearer()))
				.andExpect(status().isNoContent());
		assertThat(draftRepository.findById(draftId)).isEmpty();
		assertThat(file).doesNotExist();
		mvc.perform(get("/api/clip-drafts/" + draftId).header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
				.andExpect(status().isNotFound());
	}

	// ---------- 도우미 ----------

	/** LIVE 방송을 만들고 시작 시각을 {@code secondsAgo} 초 앞으로 옮긴다. @return broadcast id */
	private long liveBroadcast(Account streamer, int secondsAgo) {
		lifecycle.onReady(streamer.channelId(), BroadcastSource.BROWSER_PC);
		long id = broadcastRepository.findOngoing(streamer.channelId()).orElseThrow().getId();
		jdbc.update("update broadcast set started_at = started_at - interval " + secondsAgo + " second where id = ?", id);
		return id;
	}

	private ResultActions draft(Account a, long broadcastId) throws Exception {
		return mvc.perform(post("/api/broadcasts/" + broadcastId + "/clip-drafts").header(HttpHeaders.AUTHORIZATION, a.bearer()));
	}

	private int draftStatus(Account a, long broadcastId) throws Exception {
		return draft(a, broadcastId).andReturn().getResponse().getStatus();
	}

	/** 작업들을 같은 순간에 출발시켜 결과(HTTP 상태)를 모은다. */
	private static List<Integer> concurrently(ExecutorService pool, List<Callable<Integer>> tasks) throws Exception {
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (Callable<Integer> task : tasks) {
			futures.add(pool.submit(() -> {
				go.await();
				return task.call();
			}));
		}
		go.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> f : futures) {
			statuses.add(f.get(30, TimeUnit.SECONDS));
		}
		return statuses;
	}

	private ResultActions draftClip(Account a, long draftId, int start, int duration) throws Exception {
		return mvc.perform(post("/api/clip-drafts/" + draftId + "/clips").header(HttpHeaders.AUTHORIZATION, a.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"startSeconds\":" + start + ",\"durationSeconds\":" + duration + "}"));
	}
}
