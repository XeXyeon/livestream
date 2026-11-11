package com.livestream.livestream.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import com.livestream.livestream.thumbnail.FfmpegRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * REC-2 (DESIGN §4.10 서빙 · §4.16 /recordings* · §9 REC-2): 공개 목록 · 상세, 비공개는 주인만, 공개 설정 403,
 * video-token → Range 206, 토큰 검사, 삭제된 방송 404, 채널 recordingCount,
 * 공개 설정에 따른 vod/ ↔ private/ 파일 이동(REC-2#1).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecordingApiTest {

	private static final int VIDEO_SIZE = 1000;

	@TempDir static Path recordingsDir;

	@DynamicPropertySource
	static void dirs(DynamicPropertyRegistry registry) {
		registry.add("app.recording.dir", () -> recordingsDir.toString());
	}

	@Autowired MockMvc mvc;
	@Autowired BroadcastLifecycleService lifecycle;
	@Autowired BroadcastRepository broadcastRepository;
	@MockitoSpyBean RecordingRepository recordingRepository;
	@Autowired RecordingMergeService mergeService;
	@Autowired TransactionTemplate tx;
	@MockitoBean MediaMtxClient mediaMtxClient;
	@MockitoBean FfmpegRunner ffmpeg;

	@BeforeEach
	void setUp() {
		mergeService.mergeDelay = Duration.ofHours(1); // 종료 이벤트의 병합은 돌지 않게 하고 READY 는 테스트가 직접 만든다
	}

	@Test
	void 공개_목록은_READY_PUBLIC만_최신순이고_채널_recordingCount도_같다() throws Exception {
		Account a = TestAccounts.create(mvc);
		long pub = readyRecording(a, Visibility.PUBLIC);
		Account b = TestAccounts.create(mvc);
		long priv = readyRecording(b, Visibility.PRIVATE);
		Account c = TestAccounts.create(mvc);
		long processing = endedRecording(c);

		String body = mvc.perform(get("/api/recordings").param("size", "50"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<Integer> ids = JsonPath.read(body, "$.items[*].id");
		assertThat(ids).contains((int) pub).doesNotContain((int) priv, (int) processing);
		mvc.perform(get("/api/recordings").param("size", "50"))
				.andExpect(jsonPath("$.items[?(@.id == " + pub + ")].durationSeconds").value(12))
				.andExpect(jsonPath("$.items[?(@.id == " + pub + ")].channel.id").value((int) a.channelId()));

		mvc.perform(get("/api/recordings").param("sort", "popular"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		mvc.perform(get("/api/channels/" + a.channelId())).andExpect(jsonPath("$.recordingCount").value(1));
		mvc.perform(get("/api/channels/" + b.channelId())).andExpect(jsonPath("$.recordingCount").value(0));
	}

	@Test
	void 상세는_공개면_누구나_비공개면_주인만_본다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long pub = readyRecording(owner, Visibility.PUBLIC);
		String vod = recordingRepository.findById(pub).orElseThrow().getFilePath();

		mvc.perform(get("/api/recordings/" + pub))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.visibility").value("PUBLIC"))
				.andExpect(jsonPath("$.videoUrl").value("/" + vod))
				.andExpect(jsonPath("$.fileDeleted").value(false))
				.andExpect(jsonPath("$.donationTotal").value(0))
				.andExpect(jsonPath("$.isOwner").value(false));

		Account owner2 = TestAccounts.create(mvc);
		long priv = readyRecording(owner2, Visibility.PRIVATE);
		mvc.perform(get("/api/recordings/" + priv)).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(get("/api/recordings/" + priv).header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/recordings/" + priv).header(HttpHeaders.AUTHORIZATION, owner2.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.videoUrl").doesNotExist())
				.andExpect(jsonPath("$.isOwner").value(true));

		mvc.perform(get("/api/recordings/999999999")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
	}

	@Test
	void 공개_설정은_주인만_바꾸고_병합_결과를_덮어쓰지_않는다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long id = readyRecording(owner, Visibility.PUBLIC);

		mvc.perform(patch("/api/recordings/" + id).header(HttpHeaders.AUTHORIZATION, other.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(patch("/api/recordings/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(patch("/api/recordings/" + id).header(HttpHeaders.AUTHORIZATION, owner.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());

		mvc.perform(patch("/api/recordings/" + id).header(HttpHeaders.AUTHORIZATION, owner.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.visibility").value("PRIVATE"))
				.andExpect(jsonPath("$.status").value("READY"))
				.andExpect(jsonPath("$.videoUrl").doesNotExist());
		Recording r = recordingRepository.findById(id).orElseThrow();
		assertThat(r.getVisibility()).isEqualTo(Visibility.PRIVATE);
		assertThat(r.getStatus()).isEqualTo(RecordingStatus.READY);
		assertThat(r.getFilePath()).isNotNull();

		// 병합 중(PROCESSING)에 미리 정해 둘 수 있고, 상태는 그대로다
		Account owner2 = TestAccounts.create(mvc);
		long processing = endedRecording(owner2);
		mvc.perform(patch("/api/recordings/" + processing).header(HttpHeaders.AUTHORIZATION, owner2.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PUBLIC\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
	}

	@Test
	void READY면_공개_설정에_따라_영상_파일을_vod와_private_사이로_옮긴다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		long id = readyRecording(owner, Visibility.PUBLIC);
		String vod = recordingRepository.findById(id).orElseThrow().getFilePath();
		assertThat(vod).startsWith("vod/");
		String name = vod.substring("vod/".length());

		// 공개 → 비공개: vod/ 에서 private/ 로 옮기고 file_path 도 바꾼다. 예전 /vod 주소의 파일은 없다(REC-2#1)
		visibility(owner, id, "PRIVATE").andExpect(status().isOk()).andExpect(jsonPath("$.videoUrl").doesNotExist());
		assertThat(recordingRepository.findById(id).orElseThrow().getFilePath()).isEqualTo("private/" + name);
		assertThat(recordingsDir.resolve(vod)).doesNotExist();
		assertThat(recordingsDir.resolve("private/" + name)).hasSize(VIDEO_SIZE);
		// 비공개 재생(video-token)은 private/ 의 파일을 내준다
		mvc.perform(get("/api/recordings/" + id + "/video").param("token", videoToken(owner, id)))
				.andExpect(status().isOk()).andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, VIDEO_SIZE));

		// 같은 값은 아무것도 하지 않는다
		visibility(owner, id, "PRIVATE").andExpect(status().isOk());
		assertThat(recordingRepository.findById(id).orElseThrow().getFilePath()).isEqualTo("private/" + name);

		// 비공개 → 공개: 되돌린다
		visibility(owner, id, "PUBLIC").andExpect(status().isOk()).andExpect(jsonPath("$.videoUrl").value("/vod/" + name));
		assertThat(recordingRepository.findById(id).orElseThrow().getFilePath()).isEqualTo("vod/" + name);
		assertThat(recordingsDir.resolve("private/" + name)).doesNotExist();
		assertThat(recordingsDir.resolve(vod)).hasSize(VIDEO_SIZE);
	}

	@Test
	void READY가_아니면_파일을_옮기지_않고_visibility만_바꾼다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		long processing = endedRecording(owner);
		visibility(owner, processing, "PRIVATE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSING"));
		Recording r = recordingRepository.findById(processing).orElseThrow();
		assertThat(r.getVisibility()).isEqualTo(Visibility.PRIVATE);
		assertThat(r.getFilePath()).isNull();

		Account owner2 = TestAccounts.create(mvc);
		long deleted = readyRecording(owner2, Visibility.PUBLIC);
		String vod = recordingRepository.findById(deleted).orElseThrow().getFilePath();
		tx.executeWithoutResult(s -> recordingRepository.findById(deleted).orElseThrow().markFileDeleted(Instant.now()));
		visibility(owner2, deleted, "PRIVATE").andExpect(status().isOk());
		assertThat(recordingRepository.findById(deleted).orElseThrow().getFilePath()).isEqualTo(vod);
		assertThat(recordingsDir.resolve(vod)).exists();
	}

	@Test
	void 비공개인데_vod에_있으면_재생할_때_private으로_거둔다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		long id = readyRecording(owner, Visibility.PRIVATE);
		String priv = recordingRepository.findById(id).orElseThrow().getFilePath();
		String name = priv.substring("private/".length());
		// 공개로 옮긴 뒤 롤백 · 되돌리기까지 실패한 상황: 파일은 vod/(노출), DB 는 PRIVATE · private/
		Files.createDirectories(recordingsDir.resolve("vod"));
		Files.move(recordingsDir.resolve(priv), recordingsDir.resolve("vod/" + name));

		mvc.perform(get("/api/recordings/" + id + "/video").param("token", videoToken(owner, id)))
				.andExpect(status().isOk()).andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, VIDEO_SIZE));
		assertThat(recordingRepository.findById(id).orElseThrow().getFilePath()).isEqualTo(priv);
		assertThat(recordingsDir.resolve(priv)).hasSize(VIDEO_SIZE);
		assertThat(recordingsDir.resolve("vod/" + name)).doesNotExist();
	}

	@Test
	void 공개인데_file_path가_private이면_재생할_때_vod로_맞춘다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		long id = readyRecording(owner, Visibility.PUBLIC);
		String vod = recordingRepository.findById(id).orElseThrow().getFilePath();
		String name = vod.substring("vod/".length());
		// 비공개로 옮긴 뒤 롤백된 상황(그 방향은 되돌리지 않는다): 파일은 private/, DB 는 PUBLIC · vod/
		Files.createDirectories(recordingsDir.resolve("private"));
		Files.move(recordingsDir.resolve(vod), recordingsDir.resolve("private/" + name));

		mvc.perform(get("/api/recordings/" + id + "/video").param("token", videoToken(owner, id))).andExpect(status().isOk());
		assertThat(recordingRepository.findById(id).orElseThrow().getFilePath()).isEqualTo(vod);
		assertThat(recordingsDir.resolve(vod)).exists();
		assertThat(recordingsDir.resolve("private/" + name)).doesNotExist();
	}

	@Test
	void 파일_이동이_실패하면_500_FILE_MOVE_FAILED이고_아무것도_바꾸지_않는다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		long id = readyRecording(owner, Visibility.PUBLIC);
		String vod = recordingRepository.findById(id).orElseThrow().getFilePath();
		String name = vod.substring("vod/".length());
		// 옮길 자리에 비어 있지 않은 디렉터리를 두어 rename 을 실패시킨다
		Files.createDirectories(recordingsDir.resolve("private/" + name + "/blocker"));

		visibility(owner, id, "PRIVATE").andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("FILE_MOVE_FAILED"));
		Recording r = recordingRepository.findById(id).orElseThrow();
		assertThat(r.getVisibility()).isEqualTo(Visibility.PUBLIC);
		assertThat(r.getFilePath()).isEqualTo(vod);
		assertThat(recordingsDir.resolve(vod)).hasSize(VIDEO_SIZE);
	}

	@Test
	void 공개로_옮긴_뒤_DB가_롤백되면_private으로_되돌린다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		long id = readyRecording(owner, Visibility.PRIVATE);
		String priv = recordingRepository.findById(id).orElseThrow().getFilePath();
		String name = priv.substring("private/".length());
		doThrow(new IllegalStateException("DB 실패 흉내")).when(recordingRepository)
				.updateVisibilityAndFilePath(eq(id), any(), any(), any());

		visibility(owner, id, "PUBLIC").andExpect(status().isInternalServerError());

		Recording r = recordingRepository.findById(id).orElseThrow();
		assertThat(r.getVisibility()).isEqualTo(Visibility.PRIVATE);
		assertThat(r.getFilePath()).isEqualTo(priv);
		assertThat(recordingsDir.resolve(priv)).hasSize(VIDEO_SIZE);
		assertThat(recordingsDir.resolve("vod/" + name)).doesNotExist();
	}

	@Test
	void 주인은_재생_토큰으로_Range_206_응답을_받는다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long id = readyRecording(owner, Visibility.PRIVATE);

		mvc.perform(post("/api/recordings/" + id + "/video-token").header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isForbidden());
		String res = mvc.perform(post("/api/recordings/" + id + "/video-token").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(res, "$.token");
		assertThat((String) JsonPath.read(res, "$.videoUrl")).isEqualTo("/api/recordings/" + id + "/video?token=" + token);

		byte[] part = mvc.perform(get("/api/recordings/" + id + "/video").param("token", token)
						.header(HttpHeaders.RANGE, "bytes=100-199"))
				.andExpect(status().isPartialContent())
				.andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 100-199/" + VIDEO_SIZE))
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "video/mp4"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andReturn().getResponse().getContentAsByteArray();
		assertThat(part).hasSize(100);
		assertThat(part[0]).isEqualTo((byte) 100);

		mvc.perform(get("/api/recordings/" + id + "/video").param("token", token))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"))
				.andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, VIDEO_SIZE));
		mvc.perform(get("/api/recordings/" + id + "/video").param("token", token).header(HttpHeaders.RANGE, "bytes=5000-6000"))
				.andExpect(status().isRequestedRangeNotSatisfiable());
	}

	@Test
	void 재생은_그_다시보기의_재생_토큰만_받는다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		long id = readyRecording(owner, Visibility.PRIVATE);
		Account owner2 = TestAccounts.create(mvc);
		long other = readyRecording(owner2, Visibility.PRIVATE);
		String otherToken = JsonPath.read(mvc.perform(post("/api/recordings/" + other + "/video-token")
				.header(HttpHeaders.AUTHORIZATION, owner2.bearer())).andReturn().getResponse().getContentAsString(), "$.token");

		mvc.perform(get("/api/recordings/" + id + "/video")).andExpect(status().isForbidden());
		mvc.perform(get("/api/recordings/" + id + "/video").param("token", "garbage")).andExpect(status().isForbidden());
		mvc.perform(get("/api/recordings/" + id + "/video").param("token", otherToken)).andExpect(status().isForbidden());
		// 회원 access 토큰은 재생 토큰이 아니다(서명 키 · aud 분리)
		mvc.perform(get("/api/recordings/" + id + "/video").param("token", owner.access())).andExpect(status().isForbidden());
	}

	@Test
	void 준비_전이면_409_삭제된_방송이면_404() throws Exception {
		Account owner = TestAccounts.create(mvc);
		long processing = endedRecording(owner);
		mvc.perform(post("/api/recordings/" + processing + "/video-token").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORDING_NOT_READY"));

		Account owner2 = TestAccounts.create(mvc);
		long id = readyRecording(owner2, Visibility.PUBLIC);
		String token = JsonPath.read(mvc.perform(post("/api/recordings/" + id + "/video-token")
				.header(HttpHeaders.AUTHORIZATION, owner2.bearer())).andReturn().getResponse().getContentAsString(), "$.token");
		long broadcastId = recordingRepository.findById(id).orElseThrow().getBroadcast().getId();
		mvc.perform(delete("/api/broadcasts/" + broadcastId).header(HttpHeaders.AUTHORIZATION, owner2.bearer()))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/recordings/" + id)).andExpect(status().isNotFound());
		mvc.perform(get("/api/recordings/" + id + "/video").param("token", token)).andExpect(status().isNotFound());
		mvc.perform(patch("/api/recordings/" + id).header(HttpHeaders.AUTHORIZATION, owner2.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"PRIVATE\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void 보관_만료로_파일이_지워지면_fileDeleted() throws Exception {
		Account owner = TestAccounts.create(mvc);
		long id = readyRecording(owner, Visibility.PUBLIC);
		tx.executeWithoutResult(s -> recordingRepository.findById(id).orElseThrow().markFileDeleted(Instant.now()));
		mvc.perform(get("/api/recordings/" + id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FILE_DELETED"))
				.andExpect(jsonPath("$.fileDeleted").value(true))
				.andExpect(jsonPath("$.videoUrl").doesNotExist());
		mvc.perform(post("/api/recordings/" + id + "/video-token").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
				.andExpect(status().isConflict());
	}

	// ---------- 도우미 ----------

	/** 방송 시작 → 종료(PROCESSING). */
	private long endedRecording(Account a) {
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		long broadcastId = broadcastRepository.findOngoing(a.channelId()).orElseThrow().getId();
		lifecycle.endOngoingOf(a.channelId());
		return recordingRepository.findByBroadcastId(broadcastId).orElseThrow().getId();
	}

	private ResultActions visibility(Account a, long id, String value) throws Exception {
		return mvc.perform(patch("/api/recordings/" + id).header(HttpHeaders.AUTHORIZATION, a.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"" + value + "\"}"));
	}

	private String videoToken(Account a, long id) throws Exception {
		return JsonPath.read(mvc.perform(post("/api/recordings/" + id + "/video-token").header(HttpHeaders.AUTHORIZATION, a.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
	}

	/** 종료 → 병합 성공(READY, 영상 파일 0..999, 공개면 vod/ · 비공개면 private/) → 공개 설정. */
	private long readyRecording(Account a, Visibility visibility) throws Exception {
		long id = endedRecording(a);
		String relative = RecordingFiles.folderFor(visibility) + UUID.randomUUID() + ".mp4";
		byte[] bytes = new byte[VIDEO_SIZE];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) i;
		}
		Path file = recordingsDir.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
		tx.executeWithoutResult(s -> recordingRepository.findById(id).orElseThrow().markReady(relative, 12, VIDEO_SIZE));
		tx.executeWithoutResult(s -> recordingRepository.updateVisibility(id, visibility, Instant.now()));
		return id;
	}
}
