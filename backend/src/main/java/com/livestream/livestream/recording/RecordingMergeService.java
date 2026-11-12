package com.livestream.livestream.recording;

import com.livestream.livestream.broadcast.BroadcastEndedEvent;
import com.livestream.livestream.thumbnail.FfmpegRunner;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 녹화 조각 → 다시보기 mp4 병합 (DESIGN §4.10). 방송 종료 이벤트({@link BroadcastEndedEvent})로 시작한다.
 * <ul>
 *   <li>단일 스레드에서 하나씩 돈다. 종료 직후 kick 으로 닫힌 마지막 조각의 훅이 늦게 올 수 있어 {@link #MERGE_DELAY} 뒤에 시작한다.</li>
 *   <li>시작할 때 status 가 PROCESSING 이 아니면 아무것도 하지 않는다(FILE_DELETED 면 남은 raw 조각만 지운다).</li>
 *   <li>ffmpeg concat(-c copy, faststart) → 성공하면 잠금 읽기로 다시 확인해 PROCESSING 일 때만 READY + raw 조각 삭제,
 *       그 사이 FILE_DELETED 가 됐으면 만든 vod 파일을 지운다. 실패하면 FAILED + 사유(raw 조각은 남긴다).</li>
 *   <li>서버가 재시작되면 PROCESSING 으로 남은 녹화를 다시 줄 세운다.</li>
 * </ul>
 */
@Slf4j
@Service
public class RecordingMergeService {

	static final Duration MERGE_DELAY = Duration.ofSeconds(10);
	static final Duration MERGE_TIMEOUT = Duration.ofMinutes(10);
	static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
	static final String NO_SEGMENTS = "NO_SEGMENTS";
	static final String SEGMENT_FILES_MISSING = "SEGMENT_FILES_MISSING";

	private final RecordingRepository recordingRepository;
	private final RecordingSegmentRepository segmentRepository;
	private final RecordingFiles files;
	private final FfmpegRunner ffmpeg;
	private final TransactionTemplate tx;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().name("recording-merge").daemon().factory());
	/** 테스트에서 줄인다. */
	Duration mergeDelay = MERGE_DELAY;

	public RecordingMergeService(RecordingRepository recordingRepository, RecordingSegmentRepository segmentRepository,
			RecordingFiles files, FfmpegRunner ffmpeg, TransactionTemplate tx) {
		this.recordingRepository = recordingRepository;
		this.segmentRepository = segmentRepository;
		this.files = files;
		this.ffmpeg = ffmpeg;
		this.tx = tx;
	}

	@EventListener
	public void onBroadcastEnded(BroadcastEndedEvent event) {
		if (event.recordingId() != null) {
			enqueue(event.recordingId());
		}
	}

	/** 재시작 전에 줄 서 있던 병합을 되살린다. */
	@EventListener(ApplicationReadyEvent.class)
	public void resumePending() {
		List<Long> pending = recordingRepository.findProcessingIds();
		if (!pending.isEmpty()) {
			log.info("재시작 뒤 병합 대기 녹화 {}건 다시 줄 세움", pending.size());
			pending.forEach(this::enqueue);
		}
	}

	public void enqueue(Long recordingId) {
		executor.schedule(() -> {
			try {
				merge(recordingId);
			} catch (RuntimeException e) {
				if (Thread.currentThread().isInterrupted()) {
					return; // 종료 중: PROCESSING 으로 두고 재시작 때 다시
				}
				log.error("녹화 병합 중 예외: recording={}", recordingId, e);
				fail(recordingId, "ERROR: " + e);
			}
		}, mergeDelay.toMillis(), TimeUnit.MILLISECONDS);
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	private record Segment(Long id, String filePath) {
	}

	private record Snapshot(RecordingStatus status, List<Segment> segments) {
	}

	/** 병합 한 건(동기). 스레드 풀 밖에서 테스트가 직접 부른다. */
	public void merge(Long recordingId) {
		Snapshot snap = tx.execute(s -> recordingRepository.findById(recordingId)
				.map(r -> new Snapshot(r.getStatus(), segmentRepository.findByRecordingIdOrderBySequenceAsc(recordingId)
						.stream().map(seg -> new Segment(seg.getId(), seg.getFilePath())).toList()))
				.orElse(null));
		if (snap == null) {
			log.warn("병합: recording 없음: {}", recordingId);
			return;
		}
		if (snap.status() == RecordingStatus.FILE_DELETED) {
			discardSegments(recordingId);
			return;
		}
		if (snap.status() != RecordingStatus.PROCESSING) {
			log.info("병합 건너뜀(PROCESSING 아님): recording={} status={}", recordingId, snap.status());
			return;
		}
		if (snap.segments().isEmpty()) {
			fail(recordingId, NO_SEGMENTS);
			return;
		}
		List<Path> inputs = new ArrayList<>();
		for (Segment seg : snap.segments()) {
			Path p = files.resolve(seg.filePath());
			if (p != null && Files.isRegularFile(p)) {
				inputs.add(p);
			} else {
				log.warn("병합: 조각 파일 없음(건너뜀): recording={} segment={}", recordingId, seg.filePath());
			}
		}
		if (inputs.isEmpty()) {
			fail(recordingId, SEGMENT_FILES_MISSING);
			return;
		}

		String vodRelative = "vod/" + UUID.randomUUID() + ".mp4";
		Path out = files.resolve(vodRelative);
		Path list = null;
		boolean keepOut = false;
		try {
			Files.createDirectories(out.getParent());
			list = Files.createTempFile(out.getParent(), ".concat-", ".txt");
			Files.writeString(list, concatList(inputs), StandardCharsets.UTF_8);
			FfmpegRunner.Result result = ffmpeg.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-y",
					"-f", "concat", "-safe", "0", "-i", list.toString(),
					"-c", "copy", "-movflags", "+faststart", out.toString()), MERGE_TIMEOUT);
			if (Thread.currentThread().isInterrupted()) {
				// 서버 종료(shutdownNow)로 끊겼다. FAILED 로 바꾸지 않고 PROCESSING 으로 두어 재시작 때 다시 병합한다.
				log.info("병합 중단(종료 중), 재시작 뒤 다시 시도: recording={}", recordingId);
				return;
			}
			if (!result.success() || !Files.isRegularFile(out)) {
				fail(recordingId, "MERGE_FAILED " + result.describe());
				return;
			}
			Integer duration = probeDurationSeconds(out);
			long size = Files.size(out);
			SaveResult saved = tx.execute(s -> {
				Recording r = recordingRepository.findByIdForUpdate(recordingId).orElse(null);
				if (r == null || !r.isProcessing()) {
					return SaveResult.DISCARDED;
				}
				// 잠금 뒤에 센다: ffmpeg 가 도는 사이 늦게 온 조각이 있으면 이번 결과를 버리고 다시 병합한다.
				if (segmentRepository.countByRecordingId(recordingId) != snap.segments().size()) {
					return SaveResult.SEGMENTS_CHANGED;
				}
				r.markReady(vodRelative, duration, size);
				segmentRepository.deleteAllByIdInBatch(snap.segments().stream().map(Segment::id).toList());
				return SaveResult.SAVED;
			});
			switch (saved) {
				case SAVED -> {
					keepOut = true;
					snap.segments().forEach(seg -> files.deleteQuietly(seg.filePath()));
					log.info("녹화 병합 완료: recording={} file={} duration={}s size={}", recordingId, vodRelative, duration, size);
				}
				case SEGMENTS_CHANGED -> {
					log.info("병합 중 조각이 늘어 다시 병합: recording={}", recordingId);
					enqueue(recordingId);
				}
				case DISCARDED -> {
					log.info("병합 결과 버림(그 사이 상태가 바뀜, 예: 방송 삭제): recording={}", recordingId);
					discardSegmentsIfDeleted(recordingId);
				}
				case null -> {
				}
			}
		} catch (IOException e) {
			fail(recordingId, "IO " + e.getMessage());
		} finally {
			if (!keepOut) {
				deleteQuietly(out); // 실패 · 버림 · 예외(DB 오류 포함) 모두 만든 파일을 지운다
			}
			deleteQuietly(list);
		}
	}

	private enum SaveResult {
		SAVED,
		SEGMENTS_CHANGED,
		DISCARDED
	}

	/** ffmpeg concat 목록. 작은따옴표 안에서 {@code '} 는 {@code '\''} 로 쓴다. */
	static String concatList(List<Path> inputs) {
		StringBuilder sb = new StringBuilder();
		for (Path p : inputs) {
			sb.append("file '").append(p.toAbsolutePath().toString().replace("'", "'\\''")).append("'\n");
		}
		return sb.toString();
	}

	/** ffprobe 로 길이(초, 반올림). 실패하면 null(READY 는 그대로). */
	private Integer probeDurationSeconds(Path file) {
		FfmpegRunner.Result r = ffmpeg.ffprobe(List.of("-v", "error", "-show_entries", "format=duration",
				"-of", "default=noprint_wrappers=1:nokey=1", file.toString()), PROBE_TIMEOUT);
		if (!r.success()) {
			log.warn("ffprobe 실패: {} ({})", file, r.describe());
			return null;
		}
		try {
			return (int) Math.round(Double.parseDouble(r.output().strip()));
		} catch (NumberFormatException e) {
			log.warn("ffprobe 출력 해석 실패: {}", r.output());
			return null;
		}
	}

	private void fail(Long recordingId, String reason) {
		tx.executeWithoutResult(s -> recordingRepository.findByIdForUpdate(recordingId).ifPresent(r -> {
			if (r.isProcessing()) {
				r.markFailed(reason);
				log.warn("녹화 병합 실패: recording={} reason={}", recordingId, reason);
			}
		}));
	}

	private void discardSegmentsIfDeleted(Long recordingId) {
		RecordingStatus status = tx.execute(s -> recordingRepository.findById(recordingId)
				.map(Recording::getStatus).orElse(null));
		if (status == RecordingStatus.FILE_DELETED) {
			discardSegments(recordingId);
		}
	}

	/** 삭제된 녹화의 raw 조각 파일 · 행을 지운다. */
	private void discardSegments(Long recordingId) {
		List<Segment> segments = tx.execute(s -> {
			List<RecordingSegment> rows = segmentRepository.findByRecordingIdOrderBySequenceAsc(recordingId);
			segmentRepository.deleteAllInBatch(rows);
			return rows.stream().map(seg -> new Segment(seg.getId(), seg.getFilePath())).toList();
		});
		if (segments != null && !segments.isEmpty()) {
			segments.forEach(seg -> files.deleteQuietly(seg.filePath()));
			log.info("삭제된 녹화의 raw 조각 {}개 정리: recording={}", segments.size(), recordingId);
		}
	}

	private static void deleteQuietly(Path p) {
		if (p == null) {
			return;
		}
		try {
			Files.deleteIfExists(p);
		} catch (IOException ignored) {
			// 무시
		}
	}
}
