package com.livestream.livestream.clip;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.Visibility;
import com.livestream.livestream.support.TestAccounts.Account;
import com.livestream.livestream.thumbnail.FfmpegRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 클립 통합 테스트 도우미(ClipApiTest · ClipDraftApiTest). 처리기의 바깥(ffmpeg · ffprobe · MediaMTX 재생 서버)을
 * 가짜로 바꾸고, READY 다시보기를 직접 만들고, 처리가 끝날 때까지 기다린다.
 */
public final class ClipFixtures {

	public static final int VIDEO_SIZE = 1000;
	/** 가짜 ffprobe 가 돌려주는 길이(반올림하면 12초). */
	public static final String PROBED_DURATION = "12.4";

	/** 테스트가 바꾸는 가짜 동작. */
	public static final class Knobs {
		/** 참이면 다시보기 자르기(ffmpeg -ss … -t …)가 실패한다. */
		public volatile boolean failCut;
		/** 거짓이면 재생 서버가 404(그 구간 녹화 없음)인 것처럼 false 를 돌려준다. */
		public volatile boolean liveHasMedia = true;
		/** 있으면 자르기가 이 래치가 풀릴 때까지 기다린다(처리 중 상태를 붙잡아 두기). */
		public volatile CountDownLatch holdCut;
		/** 있으면 재생 서버 받기(초안)가 이 래치가 풀릴 때까지 기다린다(PENDING 상태를 붙잡아 두기). */
		public volatile CountDownLatch holdDownload;
		/**
		 * 있으면 영상 확인 ffprobe({@code -count_packets})가 파일마다 이 출력을 돌려준다(FE-3#1 · CLIP-1#3).
		 * 없으면 길이 숫자만 돌려줘 "영상 없음"으로 읽혀 {@code -c copy} 로 자른다.
		 */
		public volatile java.util.function.Function<Path, String> videoProbe;
		/** 길이 ffprobe({@code format=duration})가 돌려줄 출력. */
		public volatile String probedDuration = PROBED_DURATION;
	}

	private ClipFixtures() {
	}

	/** ffmpeg 는 마지막 인자(출력 파일)에 바이트를 쓰고 성공, ffprobe 는 {@link Knobs#probedDuration}, 재생 서버는 바이트를 쓴다. */
	public static void stub(FfmpegRunner ffmpeg, MediaMtxClient mediaMtx, Knobs knobs) {
		when(ffmpeg.ffmpeg(any(), any())).thenAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			boolean cut = args.contains("-ss") && args.contains("-t");
			if (cut) {
				CountDownLatch hold = knobs.holdCut;
				if (hold != null && !hold.await(20, TimeUnit.SECONDS)) {
					throw new IllegalStateException("holdCut 이 풀리지 않음");
				}
				if (knobs.failCut) {
					return new FfmpegRunner.Result(1, "Invalid data found when processing input", false);
				}
			}
			writeBytes(Path.of(args.getLast()));
			return new FfmpegRunner.Result(0, "", false);
		});
		when(ffmpeg.ffprobe(any(), any())).thenAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			var video = knobs.videoProbe;
			if (video != null && args.contains("-count_packets")) {
				return new FfmpegRunner.Result(0, video.apply(Path.of(args.getLast())), false);
			}
			return new FfmpegRunner.Result(0, knobs.probedDuration + "\n", false);
		});
		when(mediaMtx.downloadRecording(any(), any(), any(), any())).thenAnswer(inv -> {
			CountDownLatch hold = knobs.holdDownload;
			if (hold != null && !hold.await(20, TimeUnit.SECONDS)) {
				throw new IllegalStateException("holdDownload 이 풀리지 않음");
			}
			if (!knobs.liveHasMedia) {
				return false;
			}
			writeBytes(inv.getArgument(3));
			return true;
		});
	}

	/** 방송 시작 → 종료 → READY(영상 0..999, 길이 durationSeconds) → 공개 설정. @return recording id */
	public static long readyRecording(Account a, Visibility visibility, int durationSeconds, Path recordingsDir,
			BroadcastLifecycleService lifecycle, BroadcastRepository broadcastRepository,
			RecordingRepository recordingRepository, TransactionTemplate tx) {
		long id = endedRecording(a, lifecycle, broadcastRepository, recordingRepository);
		String relative = (visibility == Visibility.PUBLIC ? "vod/" : "private/") + UUID.randomUUID() + ".mp4";
		writeBytes(recordingsDir.resolve(relative));
		tx.executeWithoutResult(s -> recordingRepository.findById(id).orElseThrow().markReady(relative, durationSeconds, VIDEO_SIZE));
		tx.executeWithoutResult(s -> recordingRepository.updateVisibility(id, visibility, Instant.now()));
		return id;
	}

	/** 방송 시작 → 종료(recording PROCESSING). @return recording id */
	public static long endedRecording(Account a, BroadcastLifecycleService lifecycle, BroadcastRepository broadcastRepository,
			RecordingRepository recordingRepository) {
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		long broadcastId = broadcastRepository.findOngoing(a.channelId()).orElseThrow().getId();
		lifecycle.endOngoingOf(a.channelId());
		return recordingRepository.findByBroadcastId(broadcastId).orElseThrow().getId();
	}

	/** POST /recordings/{id}/clips → 202. @return clip id */
	public static long createRecordingClip(MockMvc mvc, Account a, long recordingId, String json) throws Exception {
		String body = mvc.perform(post("/api/recordings/" + recordingId + "/clips").header(HttpHeaders.AUTHORIZATION, a.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	/** POST /broadcasts/{id}/clip-drafts → 202. @return draft id */
	public static long createDraft(MockMvc mvc, Account a, long broadcastId) throws Exception {
		String body = mvc.perform(post("/api/broadcasts/" + broadcastId + "/clip-drafts").header(HttpHeaders.AUTHORIZATION, a.bearer()))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	/** 초안 받기가 끝날 때까지(PENDING 이 아닐 때까지) 초안 상세를 다시 부른다. @return 마지막 상세 JSON */
	public static String awaitDraft(MockMvc mvc, Account a, long draftId) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (true) {
			String body = mvc.perform(get("/api/clip-drafts/" + draftId).header(HttpHeaders.AUTHORIZATION, a.bearer()))
					.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			if (!"PENDING".equals(JsonPath.read(body, "$.status"))) {
				return body;
			}
			if (System.nanoTime() > deadline) {
				throw new AssertionError("클립 초안 받기가 끝나지 않음: " + body);
			}
			Thread.sleep(50);
		}
	}

	/** 초안을 만들고 READY 까지 기다린다. @return draft id */
	public static long readyDraft(MockMvc mvc, Account a, long broadcastId) throws Exception {
		long id = createDraft(mvc, a, broadcastId);
		String done = awaitDraft(mvc, a, id);
		if (!"READY".equals(JsonPath.read(done, "$.status"))) {
			throw new AssertionError("클립 초안이 READY 가 아님: " + done);
		}
		return id;
	}

	/** POST /clip-drafts/{id}/clips → 202. @return clip id */
	public static long createDraftClip(MockMvc mvc, Account a, long draftId, String json) throws Exception {
		String body = mvc.perform(post("/api/clip-drafts/" + draftId + "/clips").header(HttpHeaders.AUTHORIZATION, a.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	/** 처리가 끝날 때까지(PROCESSING 이 아닐 때까지) 상세를 다시 부른다. @return 마지막 상세 JSON */
	public static String awaitProcessed(MockMvc mvc, Account viewer, long clipId) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (true) {
			String body = mvc.perform(get("/api/clips/" + clipId).header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
					.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			if (!"PROCESSING".equals(JsonPath.read(body, "$.status"))) {
				return body;
			}
			if (System.nanoTime() > deadline) {
				throw new AssertionError("클립 처리가 끝나지 않음: " + body);
			}
			Thread.sleep(50);
		}
	}

	private static void writeBytes(Path file) {
		byte[] bytes = new byte[VIDEO_SIZE];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) i;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, bytes);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
