package com.livestream.livestream.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import com.livestream.livestream.thumbnail.FfmpegRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * REC-1 (DESIGN §4.10 · §9 REC-1): 조각 수집 훅 → 종료 → 병합(가짜 ffmpeg) → READY/FAILED, 삭제와 겹칠 때, 보관 만료.
 * 가짜 ffmpeg 는 concat 목록의 파일을 순서대로 이어 붙여 출력 파일을 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RecordingMergeTest {

	@TempDir static Path recordingsDir;

	@DynamicPropertySource
	static void dirs(DynamicPropertyRegistry registry) {
		registry.add("app.recording.dir", () -> recordingsDir.toString());
	}

	@Autowired MockMvc mvc;
	@Autowired AppProperties props;
	@Autowired BroadcastLifecycleService lifecycle;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired RecordingRepository recordingRepository;
	@MockitoSpyBean RecordingSegmentRepository segmentRepository;
	@Autowired RecordingMergeService mergeService;
	@Autowired RecordingRetentionScheduler retentionScheduler;
	@Autowired TransactionTemplate tx;
	@MockitoBean MediaMtxClient mediaMtxClient;
	@MockitoBean FfmpegRunner ffmpeg;

	@BeforeEach
	void setUp() {
		mergeService.mergeDelay = Duration.ofHours(1); // 이벤트로 줄 선 병합은 돌지 않게 하고 테스트가 merge() 를 직접 부른다
		when(ffmpeg.ffmpeg(anyList(), any())).thenAnswer(inv -> fakeConcat(inv.getArgument(0)));
		when(ffmpeg.ffprobe(anyList(), any())).thenReturn(new FfmpegRunner.Result(0, "12.6\n", false));
	}

	// ---------- 조각 수집 ----------

	@Test
	void 조각_훅은_현재_방송_녹화에_순서대로_붙고_중복_다른_채널_경로는_무시한다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		Recording rec = recordingOf(a);

		String seg1 = rawSegment(a, "s1.mp4", "AAA");
		String seg2 = rawSegment(a, "s2.mp4", "BBB");
		segmentHook(a.channelId(), seg1).andExpect(status().isNoContent());
		segmentHook(a.channelId(), seg1).andExpect(status().isNoContent()); // 중복
		segmentHook(a.channelId(), "/recordings/raw/live/" + (a.channelId() + 1) + "/x.mp4").andExpect(status().isNoContent());
		segmentHook(a.channelId(), seg2).andExpect(status().isNoContent());

		List<RecordingSegment> segs = segmentRepository.findByRecordingIdOrderBySequenceAsc(rec.getId());
		assertThat(segs).extracting(RecordingSegment::getFilePath)
				.containsExactly("raw/live/" + a.channelId() + "/s1.mp4", "raw/live/" + a.channelId() + "/s2.mp4");
		assertThat(segs).extracting(RecordingSegment::getSequence).containsExactly(1, 2);

		// 내부 토큰 없으면 거절
		mvc.perform(post("/api/internal/mediamtx/segment-complete").contentType(MediaType.APPLICATION_JSON)
				.content("{\"path\":\"live/" + a.channelId() + "\",\"segmentPath\":\"" + seg1 + "\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 종료_직후_도착한_조각도_PROCESSING_녹화에_붙는다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		end(a);
		Recording rec = recordingOf(a);
		assertThat(rec.getStatus()).isEqualTo(RecordingStatus.PROCESSING);

		segmentHook(a.channelId(), rawSegment(a, "late.mp4", "L")).andExpect(status().isNoContent());
		assertThat(segmentRepository.countByRecordingId(rec.getId())).isEqualTo(1);
	}

	// ---------- 병합 ----------

	@Test
	void 재연결로_생긴_조각_2개를_순서대로_병합해_READY가_되고_raw_조각을_지운다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		String seg1 = rawSegment(a, "s1.mp4", "first-");
		segmentHook(a.channelId(), seg1);
		lifecycle.onNotReady(a.channelId());
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC); // 유예 안 재연결 → 같은 방송
		String seg2 = rawSegment(a, "s2.mp4", "second");
		segmentHook(a.channelId(), seg2);
		end(a);
		Recording rec = recordingOf(a);

		mergeService.merge(rec.getId());

		Recording done = recordingRepository.findById(rec.getId()).orElseThrow();
		assertThat(done.getStatus()).isEqualTo(RecordingStatus.READY);
		assertThat(done.getFilePath()).matches("vod/[0-9a-f-]{36}\\.mp4"); // 채널 기본값 autoPublish → PUBLIC → vod/
		assertThat(done.getDurationSeconds()).isEqualTo(13);
		assertThat(done.getSizeBytes()).isEqualTo(12L);
		assertThat(done.getFailureReason()).isNull();
		assertThat(Files.readString(recordingsDir.resolve(done.getFilePath()))).isEqualTo("first-second");
		assertThat(Files.exists(Path.of(seg1))).isFalse();
		assertThat(Files.exists(Path.of(seg2))).isFalse();
		assertThat(segmentRepository.countByRecordingId(rec.getId())).isZero();
		// private/ 에 쓴 결과는 vod/ 로 옮겨 남지 않고, concat 목록 파일도 남기지 않는다
		String name = done.getFilePath().substring("vod/".length());
		assertThat(recordingsDir.resolve("private/" + name)).doesNotExist();
		assertThat(fileNames("private")).noneMatch(n -> n.startsWith(".concat-"));

		// 다시 불러도(이미 READY) 아무것도 하지 않는다
		mergeService.merge(rec.getId());
		assertThat(recordingRepository.findById(rec.getId()).orElseThrow().getFilePath()).isEqualTo(done.getFilePath());
	}

	@Test
	void ffmpeg는_private에_쓰고_READY_때_비공개면_private에_남긴다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		segmentHook(a.channelId(), rawSegment(a, "s1.mp4", "secret"));
		end(a);
		Recording rec = recordingOf(a);
		// 병합 중에 주인이 비공개로 바꿨다(READY 가 아니라 visibility 만 바뀐다)
		tx.executeWithoutResult(s -> recordingRepository.updateVisibility(rec.getId(), Visibility.PRIVATE, Instant.now()));
		AtomicReference<String> output = new AtomicReference<>();
		doAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			output.set(args.getLast());
			return fakeConcat(args);
		}).when(ffmpeg).ffmpeg(anyList(), any());

		mergeService.merge(rec.getId());

		assertThat(Path.of(output.get()).getParent()).isEqualTo(recordingsDir.resolve("private"));
		Recording done = recordingRepository.findById(rec.getId()).orElseThrow();
		assertThat(done.getStatus()).isEqualTo(RecordingStatus.READY);
		assertThat(done.getFilePath()).matches("private/[0-9a-f-]{36}\\.mp4");
		assertThat(Files.readString(recordingsDir.resolve(done.getFilePath()))).isEqualTo("secret");
		assertThat(recordingsDir.resolve("vod/" + done.getFilePath().substring("private/".length()))).doesNotExist();
	}

	@Test
	void vod로_옮긴_뒤_저장이_실패하면_옮긴_파일도_지운다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		segmentHook(a.channelId(), rawSegment(a, "s1.mp4", "x"));
		end(a);
		Recording rec = recordingOf(a);
		AtomicReference<String> output = new AtomicReference<>();
		doAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			output.set(args.getLast());
			return fakeConcat(args);
		}).when(ffmpeg).ffmpeg(anyList(), any());
		// 옮긴 뒤 같은 트랜잭션의 조각 행 삭제가 실패 → 롤백
		doThrow(new IllegalStateException("DB 실패 흉내")).when(segmentRepository).deleteAllByIdInBatch(any());

		assertThatThrownBy(() -> mergeService.merge(rec.getId())).isInstanceOf(IllegalStateException.class);

		assertThat(recordingRepository.findById(rec.getId()).orElseThrow().getStatus()).isEqualTo(RecordingStatus.PROCESSING);
		String name = Path.of(output.get()).getFileName().toString();
		assertThat(recordingsDir.resolve("private/" + name)).doesNotExist();
		assertThat(recordingsDir.resolve("vod/" + name)).doesNotExist();
	}

	@Test
	void 영상이_있으면_영상_없는_첫_조각을_빼고_libx264로_다시_인코딩한다() throws Exception {
		// FE-3#1: MediaMTX 드리프트 리셋으로 생긴 첫 조각은 영상 트랙만 있고 패킷이 없다(init 은 기본값 avcC).
		// 이 조각이 concat 첫 파일이면 다시보기 전체가 디코딩되지 않는다.
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		String stub = rawSegment(a, "s0.mp4", "stub-");
		String seg1 = rawSegment(a, "s1.mp4", "video");
		segmentHook(a.channelId(), stub);
		segmentHook(a.channelId(), seg1);
		end(a);
		Recording rec = recordingOf(a);
		when(ffmpeg.ffprobe(anyList(), any())).thenAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			if (!args.contains("-count_packets")) {
				return new FfmpegRunner.Result(0, "12.6\n", false);
			}
			String body = Files.readString(Path.of(args.getLast()));
			return new FfmpegRunner.Result(0, body.startsWith("stub")
					? "width=1920\nheight=1080\nnb_read_packets=N/A\nduration=2.0\n"
					: "width=1081\nheight=2400\nnb_read_packets=300\nduration=10.0\n", false);
		});
		AtomicReference<List<String>> ffmpegArgs = new AtomicReference<>();
		doAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			ffmpegArgs.set(args);
			return fakeConcat(args);
		}).when(ffmpeg).ffmpeg(anyList(), any());

		mergeService.merge(rec.getId());

		Recording done = recordingRepository.findById(rec.getId()).orElseThrow();
		assertThat(done.getStatus()).isEqualTo(RecordingStatus.READY);
		assertThat(Files.readString(recordingsDir.resolve(done.getFilePath()))).isEqualTo("video");
		assertThat(String.join(" ", ffmpegArgs.get()))
				.contains("-c:v libx264 -preset veryfast", "scale=w=864:h=1920:", "-c:a copy")
				.doesNotContain("-c copy");
		// 뺀 조각도 다른 조각처럼 지운다
		assertThat(Files.exists(Path.of(stub))).isFalse();
		assertThat(Files.exists(Path.of(seg1))).isFalse();
	}

	@Test
	void 조각_영상_확인이_실패하면_조각을_빼지_않고_raw를_남긴_채_PROBE_FAILED() throws Exception {
		// 6시간짜리 큰 조각에서 ffprobe 가 시간 초과로 실패해도 "영상 없음"으로 읽어 빼고 지우면 안 된다(리뷰 지적)
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		String stub = rawSegment(a, "s0.mp4", "stub-");
		String big = rawSegment(a, "s1.mp4", "big");
		segmentHook(a.channelId(), stub);
		segmentHook(a.channelId(), big);
		end(a);
		Recording rec = recordingOf(a);
		when(ffmpeg.ffprobe(anyList(), any())).thenAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			if (args.contains("-count_packets") && args.getLast().endsWith("s1.mp4")) {
				return new FfmpegRunner.Result(-1, "", true);
			}
			return new FfmpegRunner.Result(0, "width=1920\nheight=1080\nnb_read_packets=N/A\nduration=2.0\n", false);
		});

		mergeService.merge(rec.getId());

		Recording r = recordingRepository.findById(rec.getId()).orElseThrow();
		assertThat(r.getStatus()).isEqualTo(RecordingStatus.FAILED);
		assertThat(r.getFailureReason()).isEqualTo("PROBE_FAILED");
		assertThat(Files.exists(Path.of(stub))).isTrue();
		assertThat(Files.exists(Path.of(big))).isTrue();
		verify(ffmpeg, never()).ffmpeg(anyList(), any());
	}

	@Test
	void 영상이_없는_녹화는_그대로_복사한다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		segmentHook(a.channelId(), rawSegment(a, "s1.mp4", "audio"));
		end(a);
		Recording rec = recordingOf(a);
		when(ffmpeg.ffprobe(anyList(), any())).thenAnswer(inv -> new FfmpegRunner.Result(0,
				((List<String>) inv.getArgument(0)).contains("-count_packets") ? "duration=12.6\n" : "12.6\n", false));
		AtomicReference<List<String>> ffmpegArgs = new AtomicReference<>();
		doAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			ffmpegArgs.set(args);
			return fakeConcat(args);
		}).when(ffmpeg).ffmpeg(anyList(), any());

		mergeService.merge(rec.getId());

		assertThat(recordingRepository.findById(rec.getId()).orElseThrow().getStatus()).isEqualTo(RecordingStatus.READY);
		assertThat(String.join(" ", ffmpegArgs.get())).contains("-c copy").doesNotContain("libx264");
	}

	@Test
	void 조각이_없으면_FAILED_NO_SEGMENTS() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		end(a);
		Recording rec = recordingOf(a);

		mergeService.merge(rec.getId());

		Recording r = recordingRepository.findById(rec.getId()).orElseThrow();
		assertThat(r.getStatus()).isEqualTo(RecordingStatus.FAILED);
		assertThat(r.getFailureReason()).isEqualTo("NO_SEGMENTS");
		verify(ffmpeg, never()).ffmpeg(anyList(), any());
	}

	@Test
	void ffmpeg가_실패하면_FAILED와_사유를_남기고_raw_조각은_둔다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		String seg = rawSegment(a, "s1.mp4", "x");
		segmentHook(a.channelId(), seg);
		end(a);
		Recording rec = recordingOf(a);
		doReturn(new FfmpegRunner.Result(1, "x".repeat(500), false)).when(ffmpeg).ffmpeg(anyList(), any());

		mergeService.merge(rec.getId());

		Recording r = recordingRepository.findById(rec.getId()).orElseThrow();
		assertThat(r.getStatus()).isEqualTo(RecordingStatus.FAILED);
		assertThat(r.getFailureReason()).startsWith("MERGE_FAILED exit=1 ").hasSize(200);
		assertThat(Files.exists(Path.of(seg))).isTrue();
	}

	@Test
	void 병합_중_방송이_삭제되면_만든_vod를_지우고_raw도_정리한다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		String seg = rawSegment(a, "s1.mp4", "x");
		segmentHook(a.channelId(), seg);
		end(a);
		Recording rec = recordingOf(a);
		AtomicReference<Path> produced = new AtomicReference<>();
		doAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			FfmpegRunner.Result result = fakeConcat(args);
			produced.set(Path.of(args.getLast()));
			// ffmpeg 가 도는 사이 방송 삭제(BC-2)가 FILE_DELETED 로 바꾼다
			tx.executeWithoutResult(s -> recordingRepository.findById(rec.getId()).orElseThrow()
					.markFileDeleted(Instant.now()));
			return result;
		}).when(ffmpeg).ffmpeg(anyList(), any());

		mergeService.merge(rec.getId());

		assertThat(recordingRepository.findById(rec.getId()).orElseThrow().getStatus())
				.isEqualTo(RecordingStatus.FILE_DELETED);
		assertThat(produced.get()).isNotNull();
		assertThat(Files.exists(produced.get())).isFalse();
		assertThat(Files.exists(Path.of(seg))).isFalse();
		assertThat(segmentRepository.countByRecordingId(rec.getId())).isZero();
	}

	@Test
	void 삭제된_녹화는_병합하지_않고_raw를_지우며_늦게_온_조각도_지운다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		String seg = rawSegment(a, "s1.mp4", "x");
		segmentHook(a.channelId(), seg);
		end(a);
		Recording rec = recordingOf(a);
		tx.executeWithoutResult(s -> recordingRepository.findById(rec.getId()).orElseThrow().markFileDeleted(Instant.now()));

		mergeService.merge(rec.getId());
		assertThat(Files.exists(Path.of(seg))).isFalse();
		verify(ffmpeg, never()).ffmpeg(anyList(), any());

		String late = rawSegment(a, "late.mp4", "y");
		segmentHook(a.channelId(), late);
		assertThat(Files.exists(Path.of(late))).isFalse();
		assertThat(segmentRepository.countByRecordingId(rec.getId())).isZero();
	}

	@Test
	void 방송_종료_이벤트가_병합을_시작한다() throws Exception {
		mergeService.mergeDelay = Duration.ZERO;
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		segmentHook(a.channelId(), rawSegment(a, "s1.mp4", "ev"));
		end(a);
		Long id = recordingOf(a).getId();

		long deadline = System.currentTimeMillis() + 10_000;
		while (recordingRepository.findById(id).orElseThrow().getStatus() != RecordingStatus.READY
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		assertThat(recordingRepository.findById(id).orElseThrow().getStatus()).isEqualTo(RecordingStatus.READY);
	}

	@Test
	void 같은_조각_훅이_동시에_와도_한_번만_붙는다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		Recording rec = recordingOf(a);
		String seg = rawSegment(a, "dup.mp4", "x");

		var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
		var start = new java.util.concurrent.CountDownLatch(1);
		List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
		for (int i = 0; i < 4; i++) {
			results.add(pool.submit(() -> {
				start.await();
				return segmentHook(a.channelId(), seg).andReturn().getResponse().getStatus();
			}));
		}
		start.countDown();
		for (var f : results) {
			f.get();
		}
		pool.shutdown();

		assertThat(segmentRepository.countByRecordingId(rec.getId())).isEqualTo(1);
	}

	@Test
	void ffmpeg가_도는_사이_조각이_늘면_결과를_버리고_PROCESSING으로_다시_줄선다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		segmentHook(a.channelId(), rawSegment(a, "s1.mp4", "a"));
		end(a);
		Recording rec = recordingOf(a);
		String late = rawSegment(a, "s2.mp4", "b");
		AtomicReference<Path> produced = new AtomicReference<>();
		doAnswer(inv -> {
			List<String> args = inv.getArgument(0);
			FfmpegRunner.Result result = fakeConcat(args);
			produced.set(Path.of(args.getLast()));
			segmentHook(a.channelId(), late); // 병합 도중 늦은 조각
			return result;
		}).when(ffmpeg).ffmpeg(anyList(), any());

		mergeService.merge(rec.getId());

		assertThat(recordingRepository.findById(rec.getId()).orElseThrow().getStatus())
				.isEqualTo(RecordingStatus.PROCESSING);
		assertThat(Files.exists(produced.get())).isFalse();
		assertThat(segmentRepository.countByRecordingId(rec.getId())).isEqualTo(2);
		assertThat(Files.exists(Path.of(late))).isTrue();

		// 다음 병합은 두 조각을 모두 담는다
		doAnswer(inv -> fakeConcat(inv.getArgument(0))).when(ffmpeg).ffmpeg(anyList(), any());
		mergeService.merge(rec.getId());
		Recording done = recordingRepository.findById(rec.getId()).orElseThrow();
		assertThat(done.getStatus()).isEqualTo(RecordingStatus.READY);
		assertThat(Files.readString(recordingsDir.resolve(done.getFilePath()))).isEqualTo("ab");
	}

	@Test
	void 종료로_중단되면_FAILED로_바꾸지_않고_PROCESSING으로_둔다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		segmentHook(a.channelId(), rawSegment(a, "s1.mp4", "a"));
		end(a);
		Recording rec = recordingOf(a);
		doAnswer(inv -> {
			Thread.currentThread().interrupt(); // 러너가 interrupt 를 받아 -1 을 돌려준 상황
			return new FfmpegRunner.Result(-1, "중단됨", false);
		}).when(ffmpeg).ffmpeg(anyList(), any());

		try {
			mergeService.merge(rec.getId());
		} finally {
			Thread.interrupted(); // 테스트 스레드 플래그 정리
		}

		Recording r = recordingRepository.findById(rec.getId()).orElseThrow();
		assertThat(r.getStatus()).isEqualTo(RecordingStatus.PROCESSING);
		assertThat(fileNames("private")).filteredOn(n -> n.endsWith(".mp4"))
				.allSatisfy(n -> assertThat(recordingRepository.findAll()).anyMatch(x -> ("private/" + n).equals(x.getFilePath())));
	}

	// ---------- 보관 ----------

	@Test
	void 보관_기간이_지난_READY만_FILE_DELETED가_되고_vod를_지운다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		segmentHook(a.channelId(), rawSegment(a, "s1.mp4", "keep"));
		end(a);
		Long id = recordingOf(a).getId();
		mergeService.merge(id);
		Recording ready = recordingRepository.findById(id).orElseThrow();
		Path vod = recordingsDir.resolve(ready.getFilePath());
		assertThat(vod).exists();
		int days = props.recording().retentionDays();

		retentionScheduler.purgeExpired(ready.getCreatedAt().plus(Duration.ofDays(days)).minusSeconds(60));
		assertThat(recordingRepository.findById(id).orElseThrow().getStatus()).isEqualTo(RecordingStatus.READY);
		assertThat(vod).exists();

		Instant later = ready.getCreatedAt().plus(Duration.ofDays(days)).plusSeconds(60);
		retentionScheduler.purgeExpired(later);
		Recording purged = recordingRepository.findById(id).orElseThrow();
		assertThat(purged.getStatus()).isEqualTo(RecordingStatus.FILE_DELETED);
		assertThat(purged.getFileDeletedAt()).isNotNull();
		assertThat(vod).doesNotExist();
	}

	@Test
	void 보관_정리는_file_path와_다른_폴더의_같은_파일도_지운다() throws Exception {
		Account a = TestAccounts.create(mvc);
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		segmentHook(a.channelId(), rawSegment(a, "s1.mp4", "keep"));
		end(a);
		Long id = recordingOf(a).getId();
		mergeService.merge(id);
		Recording ready = recordingRepository.findById(id).orElseThrow();
		String name = ready.getFilePath().substring("vod/".length());
		// 옮긴 뒤 DB 커밋만 실패해 파일이 private/ 에 있는 상황
		Files.createDirectories(recordingsDir.resolve("private"));
		Files.move(recordingsDir.resolve(ready.getFilePath()), recordingsDir.resolve("private/" + name));

		retentionScheduler.purgeExpired(ready.getCreatedAt().plus(Duration.ofDays(props.recording().retentionDays())).plusSeconds(60));

		assertThat(recordingRepository.findById(id).orElseThrow().getStatus()).isEqualTo(RecordingStatus.FILE_DELETED);
		assertThat(recordingsDir.resolve("private/" + name)).doesNotExist();
		assertThat(recordingsDir.resolve("vod/" + name)).doesNotExist();
	}

	// ---------- 도우미 ----------

	/** 녹화 디렉터리의 한 폴더 안 파일 이름들. 폴더가 없으면 빈 목록. */
	private List<String> fileNames(String folder) throws IOException {
		Path dir = recordingsDir.resolve(folder);
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (var s = Files.list(dir)) {
			return s.map(p -> p.getFileName().toString()).toList();
		}
	}

	private Recording recordingOf(Account a) {
		Long id = recordingRepository.findLatestIdByChannel(a.channelId(),
				org.springframework.data.domain.PageRequest.of(0, 1)).getFirst();
		return recordingRepository.findById(id).orElseThrow();
	}

	private void end(Account a) {
		Long broadcastId = tx.execute(s -> broadcastRepository.findOngoing(a.channelId()).map(Broadcast::getId).orElseThrow());
		lifecycle.endOngoingOf(a.channelId());
		assertThat(broadcastRepository.findById(broadcastId).orElseThrow().getEndedAt()).isNotNull();
	}

	/** MediaMTX 가 녹화 디렉터리에 조각 파일을 만든 것처럼 쓴다. 반환은 MediaMTX 가 훅에 보내는 절대 경로. */
	private String rawSegment(Account a, String name, String content) throws IOException {
		Path p = recordingsDir.resolve("raw/live/" + a.channelId() + "/" + name);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content);
		return p.toString();
	}

	private ResultActions segmentHook(long channelId, String segmentPath) throws Exception {
		return mvc.perform(post("/api/internal/mediamtx/segment-complete")
				.header("X-Internal-Token", props.mediamtx().internalToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"path\":\"live/" + channelId + "\",\"segmentPath\":\"" + segmentPath + "\"}"));
	}

	/** {@code -i <list>} 의 파일들을 이어 붙여 마지막 인자(출력)에 쓴다. */
	private static FfmpegRunner.Result fakeConcat(List<String> args) {
		try {
			Path list = Path.of(args.get(args.indexOf("-i") + 1));
			StringBuilder out = new StringBuilder();
			for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
				String path = line.substring("file '".length(), line.length() - 1).replace("'\\''", "'");
				out.append(Files.readString(Path.of(path)));
			}
			Files.writeString(Path.of(args.getLast()), out.toString());
			return new FfmpegRunner.Result(0, "", false);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
