package com.livestream.livestream.clip;

import static com.livestream.livestream.clip.ClipFixtures.awaitProcessed;
import static com.livestream.livestream.clip.ClipFixtures.createRecordingClip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.recording.RecordingMergeService;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.Visibility;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import com.livestream.livestream.thumbnail.FfmpegRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CLIP-1 (DESIGN §4.18 · §4.16 클립 행 · §4.8 · §9 CLIP-1 완료 ①~⑨): 다시보기 · 라이브(CLIP-3 초안 경로) 클립 생성 조건, 처리(가짜
 * ffmpeg · 재생 서버) 뒤 READY · 파일 위치, 비공개 토큰 재생, 공개 설정 이동, 삭제, 목록 세 개, 방송 삭제 연동, 처리 실패.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClipApiTest {

	private static final int RECORDING_SECONDS = 120;

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
	@Autowired RecordingRepository recordingRepository;
	@Autowired ClipRepository clipRepository;
	@Autowired RecordingMergeService mergeService;
	@Autowired TransactionTemplate tx;
	@Autowired ClipProcessingService processing;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean MediaMtxClient mediaMtxClient;
	@MockitoBean FfmpegRunner ffmpeg;

	private final ClipFixtures.Knobs knobs = new ClipFixtures.Knobs();

	@BeforeEach
	void setUp() {
		// 종료 이벤트의 병합은 돌지 않게 하고 READY 는 테스트가 직접 만든다
		ReflectionTestUtils.setField(mergeService, "mergeDelay", Duration.ofHours(1));
		ClipFixtures.stub(ffmpeg, mediaMtxClient, knobs);
	}

	// ① 다시보기 클립 → READY, /vod/clips/…, 파일 · 썸네일
	@Test
	void 다시보기_클립은_202_뒤_처리되어_READY가_되고_공개_폴더에_파일과_썸네일이_생긴다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long recordingId = readyRecording(owner, Visibility.PUBLIC);

		String created = mvc.perform(post("/api/recordings/" + recordingId + "/clips").header(HttpHeaders.AUTHORIZATION, viewer.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"   \",\"startSeconds\":10,\"durationSeconds\":20}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PROCESSING"))
				.andExpect(jsonPath("$.source").value("RECORDING"))
				.andExpect(jsonPath("$.visibility").value("PUBLIC"))
				.andExpect(jsonPath("$.startSeconds").value(10))
				.andExpect(jsonPath("$.durationSeconds").value(20))
				.andExpect(jsonPath("$.videoUrl").doesNotExist())
				.andExpect(jsonPath("$.recordingId").value((int) recordingId))
				.andExpect(jsonPath("$.creator.nickname").value(viewer.nickname()))
				.andExpect(jsonPath("$.channel.id").value((int) owner.channelId()))
				.andExpect(jsonPath("$.isCreator").value(true))
				.andExpect(jsonPath("$.isOwner").value(false))
				.andReturn().getResponse().getContentAsString();
		long clipId = ((Number) JsonPath.read(created, "$.id")).longValue();
		String broadcastTitle = JsonPath.read(created, "$.title");
		assertThat(broadcastTitle).isNotBlank(); // 공백 제목 → 방송 제목

		String done = awaitProcessed(mvc, viewer, clipId);
		assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("READY");
		String videoUrl = JsonPath.read(done, "$.videoUrl");
		assertThat(videoUrl).startsWith("/vod/clips/").endsWith(".mp4");
		assertThat(recordingsDir.resolve(videoUrl.substring(1))).isRegularFile();
		assertThat((Integer) JsonPath.read(done, "$.durationSeconds")).isEqualTo(12); // ffprobe 실제 길이
		assertThat((String) JsonPath.read(done, "$.thumbnailUrl")).isEqualTo("/uploads/thumbnails/clips/" + clipId + ".jpg");
		assertThat(uploadsDir.resolve("thumbnails/clips/" + clipId + ".jpg")).isRegularFile();
		assertThat((Boolean) JsonPath.read(done, "$.recordingAvailable")).isTrue();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
		verify(ffmpeg, timeout(1000).atLeastOnce()).ffmpeg(args.capture(), any());
		List<String> cut = args.getAllValues().stream().filter(a -> a.contains("-avoid_negative_ts")).findFirst().orElseThrow();
		assertThat(String.join(" ", cut)).contains("-ss 10 -i ").contains(" -t 20 -c copy -avoid_negative_ts make_zero -movflags +faststart ");

		// 비로그인도 공개 클립 상세를 본다
		mvc.perform(get("/api/clips/" + clipId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.videoUrl").value(videoUrl))
				.andExpect(jsonPath("$.isCreator").value(false));
	}

	// ② 비공개 클립: private/clips/, videoUrl null, 토큰 재생, 다른 회원 403
	@Test
	void 비공개_클립은_private_폴더에_있고_만든_회원과_채널_주인만_토큰으로_재생한다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account creator = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long recordingId = readyRecording(owner, Visibility.PUBLIC);
		long clipId = createRecordingClip(mvc, creator, recordingId,
				"{\"title\":\"내 비공개\",\"startSeconds\":0,\"durationSeconds\":5,\"visibility\":\"PRIVATE\"}");
		String done = awaitProcessed(mvc, creator, clipId);
		assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("READY");
		assertThat((String) JsonPath.read(done, "$.videoUrl")).isNull();
		String filePath = clipRepository.findById(clipId).orElseThrow().getFilePath();
		assertThat(filePath).startsWith("private/clips/");
		assertThat(recordingsDir.resolve(filePath)).isRegularFile();

		mvc.perform(get("/api/clips/" + clipId)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(get("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, other.bearer())).andExpect(status().isForbidden());
		mvc.perform(get("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, owner.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.isOwner").value(true));
		mvc.perform(post("/api/clips/" + clipId + "/video-token").header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isForbidden());

		String token = videoToken(creator, clipId);
		mvc.perform(get("/api/clips/" + clipId + "/video").param("token", token))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"));
		mvc.perform(get("/api/clips/" + clipId + "/video").param("token", token).header(HttpHeaders.RANGE, "bytes=0-99"))
				.andExpect(status().isPartialContent())
				.andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-99/" + ClipFixtures.VIDEO_SIZE));
		videoToken(owner, clipId); // 채널 주인도 받는다

		mvc.perform(get("/api/clips/" + clipId + "/video")).andExpect(status().isForbidden());
		mvc.perform(get("/api/clips/" + clipId + "/video").param("token", other.access())).andExpect(status().isForbidden());
		// 다른 클립의 토큰 · 다시보기 재생 토큰과 섞이지 않는다
		mvc.perform(get("/api/clips/" + (clipId + 1000) + "/video").param("token", token)).andExpect(status().isForbidden());
	}

	// ③ 다시보기 클립 조건
	@Test
	void 다시보기_클립_조건_비공개_원본_준비_전_구간_길이_동시_처리() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long privateRecording = readyRecording(owner, Visibility.PRIVATE);
		clip(viewer, privateRecording, 0, 10).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(post("/api/recordings/" + privateRecording + "/clips").contentType(MediaType.APPLICATION_JSON)
				.content("{\"startSeconds\":0,\"durationSeconds\":10}")).andExpect(status().isUnauthorized());
		long ownClip = createRecordingClip(mvc, owner, privateRecording, "{\"startSeconds\":0,\"durationSeconds\":10}");
		awaitProcessed(mvc, owner, ownClip);

		Account owner2 = TestAccounts.create(mvc);
		long processing = ClipFixtures.endedRecording(owner2, lifecycle, broadcastRepository, recordingRepository);
		clip(viewer, processing, 0, 10).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORDING_NOT_READY"));

		Account owner3 = TestAccounts.create(mvc); // 같은 채널은 종료 뒤 30초 동안 다시 시작할 수 없다(§4.6)
		long pub = readyRecording(owner3, Visibility.PUBLIC);
		clip(viewer, pub, RECORDING_SECONDS - 10, 11).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value(ClipService.OUT_OF_RANGE));
		clip(viewer, pub, -1, 10).andExpect(status().isBadRequest());
		clip(viewer, pub, 0, 4).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("클립 길이는 5~60초입니다"));
		clip(viewer, pub, 0, 61).andExpect(status().isBadRequest());
		mvc.perform(post("/api/recordings/" + pub + "/clips").header(HttpHeaders.AUTHORIZATION, viewer.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"durationSeconds\":10}")).andExpect(status().isBadRequest());
		mvc.perform(post("/api/recordings/999999999/clips").header(HttpHeaders.AUTHORIZATION, viewer.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"startSeconds\":0,\"durationSeconds\":10}"))
				.andExpect(status().isNotFound());

		// 처리 중인 내 클립이 있으면 409, 끝나면 다시 만들 수 있다
		knobs.holdCut = new CountDownLatch(1);
		long first = createRecordingClip(mvc, viewer, pub, "{\"startSeconds\":0,\"durationSeconds\":10}");
		clip(viewer, pub, 10, 10).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIP_IN_PROGRESS"));
		mvc.perform(post("/api/clips/" + first + "/video-token").header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIP_NOT_READY"));
		createRecordingClip(mvc, owner2, pub, "{\"startSeconds\":0,\"durationSeconds\":10}"); // 다른 회원은 상관없다
		knobs.holdCut.countDown();
		awaitProcessed(mvc, viewer, first);
		knobs.holdCut = null;
		long second = createRecordingClip(mvc, viewer, pub, "{\"startSeconds\":110,\"durationSeconds\":10}");
		awaitProcessed(mvc, viewer, second);
	}

	// ④ 라이브 클립(CLIP-3): 재생 서버를 바로 부르지 않고 초안 파일에서 자른다
	@Test
	void 라이브_클립은_초안_파일에서_잘리고_초안_파일이_없으면_SOURCE_FILE_MISSING() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		lifecycle.onReady(streamer.channelId(), BroadcastSource.BROWSER_PC);
		long broadcastId = broadcastRepository.findOngoing(streamer.channelId()).orElseThrow().getId();
		jdbc.update("update broadcast set started_at = started_at - interval 300 second where id = ?", broadcastId);
		long draftId = ClipFixtures.readyDraft(mvc, viewer, broadcastId);
		Integer windowStart = jdbc.queryForObject("select window_start_seconds from clip_draft where id = ?", Integer.class, draftId);
		String draftFile = jdbc.queryForObject("select file_path from clip_draft where id = ?", String.class, draftId);
		assertThat(draftFile).startsWith("private/clip-drafts/");

		String created = mvc.perform(post("/api/clip-drafts/" + draftId + "/clips").header(HttpHeaders.AUTHORIZATION, viewer.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"startSeconds\":3,\"durationSeconds\":5}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.source").value("LIVE"))
				.andExpect(jsonPath("$.status").value("PROCESSING"))
				.andExpect(jsonPath("$.startSeconds").value(windowStart + 3)) // 방송 시작 기준으로 바꿔 저장
				.andExpect(jsonPath("$.broadcastId").value((int) broadcastId))
				.andExpect(jsonPath("$.recordingAvailable").value(false))
				.andReturn().getResponse().getContentAsString();
		long clipId = ((Number) JsonPath.read(created, "$.id")).longValue();
		String done = awaitProcessed(mvc, viewer, clipId);
		assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("READY");
		assertThat((String) JsonPath.read(done, "$.videoUrl")).startsWith("/vod/clips/");

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
		verify(ffmpeg, timeout(1000).atLeastOnce()).ffmpeg(args.capture(), any());
		List<String> cut = args.getAllValues().stream().filter(a -> a.contains("-avoid_negative_ts")).findFirst().orElseThrow();
		assertThat(String.join(" ", cut)).contains("-ss 3 -i ").contains(draftFile + " -t 5 -c copy"); // 초안 기준 시작
		// 재생 서버는 초안 받기에서 한 번만 부른다(창 전체)
		verify(mediaMtxClient, times(1)).downloadRecording(eq("live/" + streamer.channelId()), any(), eq(Duration.ofSeconds(120)), any());

		// 초안 파일이 없어졌으면 FAILED(SOURCE_FILE_MISSING)
		Files.delete(recordingsDir.resolve(draftFile));
		long missing = ClipFixtures.createDraftClip(mvc, viewer, draftId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		String failed = awaitProcessed(mvc, viewer, missing);
		assertThat((String) JsonPath.read(failed, "$.status")).isEqualTo("FAILED");
		assertThat((String) JsonPath.read(failed, "$.failureReason")).isEqualTo("SOURCE_FILE_MISSING");

		// "최근 N초" 원클릭 엔드포인트는 없어졌다
		mvc.perform(post("/api/broadcasts/" + broadcastId + "/clips").header(HttpHeaders.AUTHORIZATION, viewer.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"durationSeconds\":10}"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
	}

	// ⑤ 공개 설정 · 제목 수정
	@Test
	void 공개_설정을_바꾸면_파일이_옮겨지고_채널_주인도_고칠_수_있고_제3자는_403() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account creator = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long recordingId = readyRecording(owner, Visibility.PUBLIC);
		long clipId = createRecordingClip(mvc, creator, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		awaitProcessed(mvc, creator, clipId);
		String publicPath = clipRepository.findById(clipId).orElseThrow().getFilePath();
		assertThat(publicPath).startsWith("vod/clips/");

		// 제3자(만든 회원도 채널 주인도 아님) → 403 (§4.8 클립 행)
		patchClip(other, clipId, "{\"visibility\":\"PRIVATE\"}").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		patchClip(other, clipId, "{\"title\":\"남의 클립\"}").andExpect(status().isForbidden());
		mvc.perform(delete("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, other.bearer())).andExpect(status().isForbidden());
		mvc.perform(post("/api/clips/" + clipId + "/video-token").header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isForbidden());
		mvc.perform(patch("/api/clips/" + clipId).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
				.andExpect(status().isUnauthorized());

		// 채널 주인(만든 회원 아님)이 비공개로
		patchClip(owner, clipId, "{\"visibility\":\"PRIVATE\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.videoUrl").doesNotExist());
		String privatePath = clipRepository.findById(clipId).orElseThrow().getFilePath();
		assertThat(privatePath).isEqualTo(ClipFiles.videoPathFor(publicPath, Visibility.PRIVATE));
		assertThat(recordingsDir.resolve(privatePath)).isRegularFile();
		assertThat(recordingsDir.resolve(publicPath)).doesNotExist();
		mvc.perform(get("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, other.bearer())).andExpect(status().isForbidden());
		mvc.perform(get("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, creator.bearer())).andExpect(status().isOk());

		// 만든 회원이 제목과 공개로
		patchClip(creator, clipId, "{\"title\":\"  새 제목  \",\"visibility\":\"PUBLIC\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("새 제목"))
				.andExpect(jsonPath("$.videoUrl").value("/" + publicPath));
		assertThat(recordingsDir.resolve(publicPath)).isRegularFile();
		assertThat(recordingsDir.resolve(privatePath)).doesNotExist();

		// 같은 값 · 제목만
		patchClip(creator, clipId, "{\"visibility\":\"PUBLIC\"}").andExpect(status().isOk());
		patchClip(owner, clipId, "{\"title\":\"주인이 바꿈\"}").andExpect(status().isOk()).andExpect(jsonPath("$.title").value("주인이 바꿈"));
		patchClip(creator, clipId, "{\"title\":\"" + "가".repeat(101) + "\"}").andExpect(status().isBadRequest());

		// 처리 중이면 값만 바뀌고 READY 때 그 폴더로 간다
		knobs.holdCut = new CountDownLatch(1);
		long pending = createRecordingClip(mvc, creator, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		patchClip(creator, pending, "{\"visibility\":\"PRIVATE\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PROCESSING")).andExpect(jsonPath("$.visibility").value("PRIVATE"));
		knobs.holdCut.countDown();
		awaitProcessed(mvc, creator, pending);
		assertThat(clipRepository.findById(pending).orElseThrow().getFilePath()).startsWith("private/clips/");
	}

	// ⑥ 삭제
	@Test
	void 삭제하면_404이고_파일과_썸네일이_지워진다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account creator = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long recordingId = readyRecording(owner, Visibility.PUBLIC);
		long clipId = createRecordingClip(mvc, creator, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		awaitProcessed(mvc, creator, clipId);
		Path video = recordingsDir.resolve(clipRepository.findById(clipId).orElseThrow().getFilePath());
		Path thumbnail = uploadsDir.resolve("thumbnails/clips/" + clipId + ".jpg");
		assertThat(video).isRegularFile();
		assertThat(thumbnail).isRegularFile();

		mvc.perform(delete("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, other.bearer())).andExpect(status().isForbidden());
		mvc.perform(delete("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, creator.bearer())).andExpect(status().isNoContent());
		mvc.perform(get("/api/clips/" + clipId)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CLIP_NOT_FOUND"));
		mvc.perform(delete("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, creator.bearer())).andExpect(status().isNotFound());
		assertThat(video).doesNotExist();
		assertThat(thumbnail).doesNotExist();

		// 채널 주인도 남이 만든 클립을 지운다
		long second = createRecordingClip(mvc, creator, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		awaitProcessed(mvc, creator, second);
		mvc.perform(delete("/api/clips/" + second).header(HttpHeaders.AUTHORIZATION, owner.bearer())).andExpect(status().isNoContent());

		// 처리 중에 지우면 처리기가 결과를 버린다
		knobs.holdCut = new CountDownLatch(1);
		long pending = createRecordingClip(mvc, creator, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		mvc.perform(delete("/api/clips/" + pending).header(HttpHeaders.AUTHORIZATION, creator.bearer())).andExpect(status().isNoContent());
		knobs.holdCut.countDown();
		awaitClipFilesGone(pending);
		assertThat(clipRepository.findById(pending).orElseThrow().getFilePath()).isNull();
	}

	// ⑦ 목록 세 개
	@Test
	void 목록은_공개만_주인은_비공개_포함_내_클립은_상태_포함() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long recordingId = readyRecording(owner, Visibility.PUBLIC);
		long publicClip = createRecordingClip(mvc, viewer, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		awaitProcessed(mvc, viewer, publicClip);
		long privateClip = createRecordingClip(mvc, owner, recordingId,
				"{\"startSeconds\":0,\"durationSeconds\":5,\"visibility\":\"PRIVATE\"}");
		awaitProcessed(mvc, owner, privateClip);
		knobs.failCut = true;
		long failedClip = createRecordingClip(mvc, viewer, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		awaitProcessed(mvc, viewer, failedClip);
		knobs.failCut = false;

		assertThat(ids(mvc.perform(get("/api/channels/" + owner.channelId() + "/clips")))).containsExactly(publicClip);
		assertThat(ids(mvc.perform(get("/api/channels/" + owner.channelId() + "/clips").header(HttpHeaders.AUTHORIZATION, viewer.bearer()))))
				.containsExactly(publicClip);
		assertThat(ids(mvc.perform(get("/api/channels/" + owner.channelId() + "/clips").header(HttpHeaders.AUTHORIZATION, owner.bearer()))))
				.containsExactly(privateClip, publicClip);
		assertThat(ids(mvc.perform(get("/api/recordings/" + recordingId + "/clips")))).containsExactly(publicClip);
		assertThat(ids(mvc.perform(get("/api/recordings/" + recordingId + "/clips").header(HttpHeaders.AUTHORIZATION, owner.bearer()))))
				.containsExactly(privateClip, publicClip);
		mvc.perform(get("/api/channels/" + owner.channelId() + "/clips"))
				.andExpect(jsonPath("$.items[0].creator.nickname").value(viewer.nickname()))
				.andExpect(jsonPath("$.items[0].thumbnailUrl").value("/uploads/thumbnails/clips/" + publicClip + ".jpg"))
				.andExpect(jsonPath("$.items[0].recordingId").value((int) recordingId))
				.andExpect(jsonPath("$.totalElements").value(1));

		mvc.perform(get("/api/members/me/clips").header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
				.andExpect(jsonPath("$.items[0].id").value((int) failedClip))
				.andExpect(jsonPath("$.items[0].status").value("FAILED"))
				.andExpect(jsonPath("$.items[1].id").value((int) publicClip))
				.andExpect(jsonPath("$.totalElements").value(2));
		mvc.perform(get("/api/members/me/clips")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/channels/999999999/clips")).andExpect(status().isNotFound());
	}

	// ⑧ 방송 삭제
	@Test
	void 방송을_삭제하면_그_방송의_클립도_지워진다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long recordingId = readyRecording(owner, Visibility.PUBLIC);
		long broadcastId = recordingRepository.findById(recordingId).orElseThrow().getBroadcast().getId();
		long clipId = createRecordingClip(mvc, viewer, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		awaitProcessed(mvc, viewer, clipId);
		Path video = recordingsDir.resolve(clipRepository.findById(clipId).orElseThrow().getFilePath());
		Path thumbnail = uploadsDir.resolve("thumbnails/clips/" + clipId + ".jpg");

		mvc.perform(delete("/api/broadcasts/" + broadcastId).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/clips/" + clipId)).andExpect(status().isNotFound());
		assertThat(clipRepository.findById(clipId).orElseThrow().isDeleted()).isTrue();
		assertThat(video).doesNotExist();
		assertThat(thumbnail).doesNotExist();
		assertThat(ids(mvc.perform(get("/api/members/me/clips").header(HttpHeaders.AUTHORIZATION, viewer.bearer())))).isEmpty();

		// 생성과 방송 삭제가 겹친 경우(삭제가 아직 없는 클립을 못 보고 먼저 커밋): 처리기가 방송을 다시 보고 클립을 버린다
		Account owner2 = TestAccounts.create(mvc);
		long recording2 = readyRecording(owner2, Visibility.PUBLIC);
		long broadcast2 = recordingRepository.findById(recording2).orElseThrow().getBroadcast().getId();
		knobs.holdCut = new CountDownLatch(1);
		long raced = createRecordingClip(mvc, viewer, recording2, "{\"startSeconds\":0,\"durationSeconds\":5}");
		jdbc.update("update broadcast set deleted_at = now(6) where id = ?", broadcast2);
		knobs.holdCut.countDown();
		knobs.holdCut = null;
		awaitClipFilesGone(raced);
		Clip discarded = clipRepository.findById(raced).orElseThrow();
		assertThat(discarded.isDeleted()).isTrue();
		assertThat(discarded.getFilePath()).isNull();
		mvc.perform(get("/api/clips/" + raced)).andExpect(status().isNotFound());
	}

	// ⑨ 처리 실패
	@Test
	void 처리가_실패하면_FAILED이고_사유는_만든_회원과_채널_주인에게만_보인다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account creator = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long recordingId = readyRecording(owner, Visibility.PUBLIC);
		knobs.failCut = true;
		long clipId = createRecordingClip(mvc, creator, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		String done = awaitProcessed(mvc, creator, clipId);
		assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("FAILED");
		assertThat((String) JsonPath.read(done, "$.failureReason")).isEqualTo("CUT_FAILED"); // 코드만(경로 · 내부 주소는 로그에만)
		assertThat((String) JsonPath.read(done, "$.videoUrl")).isNull();

		mvc.perform(get("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, other.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.failureReason").doesNotExist());
		mvc.perform(get("/api/clips/" + clipId)).andExpect(jsonPath("$.failureReason").doesNotExist());
		mvc.perform(get("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
				.andExpect(jsonPath("$.failureReason").value("CUT_FAILED"));
		// 실패한 클립은 동시 처리 수에 들지 않는다
		knobs.failCut = false;
		long next = createRecordingClip(mvc, creator, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		awaitProcessed(mvc, creator, next);
	}

	// ⑩ 갇힌 클립 정리
	@Test
	void 처리기_집합에_없는_오래된_PROCESSING은_failStale_뒤_TIMEOUT이고_새_클립을_만들_수_있다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account creator = TestAccounts.create(mvc);
		long recordingId = readyRecording(owner, Visibility.PUBLIC);
		// 처리 끝난 클립을 PROCESSING 으로 되돌려 "처리기 집합에 없는 PROCESSING"(갇힌 클립)을 만든다
		long stuck = createRecordingClip(mvc, creator, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		awaitProcessed(mvc, creator, stuck);
		jdbc.update("update clip set status = 'PROCESSING' where id = ?", stuck);
		clip(creator, recordingId, 0, 5).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLIP_IN_PROGRESS"));

		// 유예(app.clip.timeout) 안이면 그대로
		processing.failStale(Instant.now());
		assertThat(clipRepository.findById(stuck).orElseThrow().getStatus()).isEqualTo(ClipStatus.PROCESSING);

		// 유예가 지나도 재시작 복구(resumePending) 전이면 그대로(되살릴 클립을 TIMEOUT 으로 만들지 않게)
		// (스케줄러가 끼어들지 않게 막은 뒤 created_at 을 옮긴다)
		ReflectionTestUtils.setField(processing, "resumed", false);
		try {
			jdbc.update("update clip set created_at = created_at - interval 10 minute where id = ?", stuck);
			assertThat(processing.failStale(Instant.now())).isZero();
		} finally {
			ReflectionTestUtils.setField(processing, "resumed", true);
		}
		assertThat(clipRepository.findById(stuck).orElseThrow().getStatus()).isEqualTo(ClipStatus.PROCESSING);

		// 복구 뒤 유예가 지난 것은 FAILED(TIMEOUT), 그 회원은 다시 만들 수 있다
		processing.failStale(Instant.now());
		mvc.perform(get("/api/clips/" + stuck).header(HttpHeaders.AUTHORIZATION, creator.bearer()))
				.andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.failureReason").value("TIMEOUT"));

		// 처리기 집합에 있는(처리 중인) 클립은 오래돼도 그대로
		knobs.holdCut = new CountDownLatch(1);
		long held = createRecordingClip(mvc, creator, recordingId, "{\"startSeconds\":0,\"durationSeconds\":5}");
		jdbc.update("update clip set created_at = created_at - interval 10 minute where id = ?", held);
		processing.failStale(Instant.now());
		assertThat(clipRepository.findById(held).orElseThrow().getStatus()).isEqualTo(ClipStatus.PROCESSING);
		knobs.holdCut.countDown();
		knobs.holdCut = null;
		assertThat((String) JsonPath.read(awaitProcessed(mvc, creator, held), "$.status")).isEqualTo("READY");
	}

	// ---------- 도우미 ----------

	private long readyRecording(Account a, Visibility visibility) {
		return ClipFixtures.readyRecording(a, visibility, RECORDING_SECONDS, recordingsDir, lifecycle, broadcastRepository,
				recordingRepository, tx);
	}

	private ResultActions clip(Account a, long recordingId, int start, int duration) throws Exception {
		return mvc.perform(post("/api/recordings/" + recordingId + "/clips").header(HttpHeaders.AUTHORIZATION, a.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"startSeconds\":" + start + ",\"durationSeconds\":" + duration + "}"));
	}

	private ResultActions patchClip(Account a, long clipId, String json) throws Exception {
		return mvc.perform(patch("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, a.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private String videoToken(Account a, long clipId) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/clips/" + clipId + "/video-token").header(HttpHeaders.AUTHORIZATION, a.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.videoUrl").value(org.hamcrest.Matchers.startsWith("/api/clips/" + clipId + "/video?token=")))
				.andReturn().getResponse().getContentAsString(), "$.token");
	}

	private static List<Long> ids(ResultActions result) throws Exception {
		List<Number> ids = JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	/**
	 * 삭제된 클립의 처리가 끝날 때까지 기다린다: 처리기는 스레드 하나라 뒤에 줄 세운 클립이 끝나면 앞의 것도 끝났다.
	 * 처리기는 썸네일까지 만든 뒤 잠금 읽기에서 삭제를 보고 영상 · 썸네일을 지운다.
	 */
	private void awaitClipFilesGone(long clipId) throws Exception {
		Account probeOwner = TestAccounts.create(mvc);
		Account probe = TestAccounts.create(mvc);
		long rec = readyRecording(probeOwner, Visibility.PUBLIC);
		awaitProcessed(mvc, probe, createRecordingClip(mvc, probe, rec, "{\"startSeconds\":0,\"durationSeconds\":5}"));
		assertThat(uploadsDir.resolve("thumbnails/clips/" + clipId + ".jpg")).doesNotExist();
	}
}
