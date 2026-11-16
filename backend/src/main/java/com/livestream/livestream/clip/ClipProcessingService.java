package com.livestream.livestream.clip;

import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.config.AsyncConfig;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.recording.RecordingFiles;
import com.livestream.livestream.recording.Visibility;
import com.livestream.livestream.thumbnail.FfmpegRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * 클립 처리기 (DESIGN §4.18). {@code clipExecutor}(단일 스레드)에서 하나씩 돈다.
 * <ul>
 *   <li>시작할 때 클립을 다시 읽어 PROCESSING 이 아니거나 삭제됐으면 아무것도 하지 않는다.</li>
 *   <li>RECORDING: 병합된 다시보기 파일을 {@code ffmpeg -ss -i -t -c copy} 로 자른다(재인코딩 없음, 시작은 직전 키프레임으로 당겨질 수 있다).</li>
 *   <li>LIVE: MediaMTX 재생 서버에서 {@code [started_at + start_seconds, +duration)} 구간 mp4 를 받는다. 404 · 빈 응답이면 FAILED(NO_MEDIA).</li>
 *   <li>결과는 항상 {@code private/clips/} 에 먼저 쓴다. ffprobe 길이가 0 이면 FAILED(EMPTY). 썸네일은 실패해도 READY(썸네일만 null).</li>
 *   <li>새 트랜잭션에서 클립을 잠그고 PROCESSING · 삭제 안 됨일 때만 READY. 그때의 visibility 가 PUBLIC 이면 {@code vod/clips/} 로
 *       옮긴 뒤 경로를 적는다. 그 사이 삭제됐으면 만든 파일을 지운다. 실패는 FAILED + 사유(앞 200자).</li>
 *   <li>서버가 재시작되면 PROCESSING 클립을 다시 줄 세운다(LIVE 도 저장된 구간으로 같은 범위를 받는다).</li>
 * </ul>
 */
@Slf4j
@Service
public class ClipProcessingService {

	static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);
	static final Duration THUMBNAIL_TIMEOUT = Duration.ofSeconds(30);
	static final String NO_MEDIA = "NO_MEDIA";
	static final String EMPTY = "EMPTY";
	static final String SOURCE_FILE_MISSING = "SOURCE_FILE_MISSING";
	// 실패 사유는 코드만 저장한다(클립을 만든 아무 회원에게나 응답되므로 경로 · 내부 주소 · 예외는 로그에만)
	static final String CUT_FAILED = "CUT_FAILED";
	static final String DOWNLOAD_FAILED = "DOWNLOAD_FAILED";
	static final String IO_ERROR = "IO_ERROR";
	static final String ERROR = "ERROR";

	private final ClipRepository clipRepository;
	private final ClipFiles files;
	private final RecordingFiles recordingFiles;
	private final FfmpegRunner ffmpeg;
	private final MediaMtxClient mediaMtxClient;
	private final TransactionTemplate tx;
	private final AppProperties props;
	private final TaskExecutor executor;

	public ClipProcessingService(ClipRepository clipRepository, ClipFiles files, RecordingFiles recordingFiles,
			FfmpegRunner ffmpeg, MediaMtxClient mediaMtxClient, TransactionTemplate tx, AppProperties props,
			@Qualifier(AsyncConfig.CLIP_EXECUTOR) TaskExecutor executor) {
		this.clipRepository = clipRepository;
		this.files = files;
		this.recordingFiles = recordingFiles;
		this.ffmpeg = ffmpeg;
		this.mediaMtxClient = mediaMtxClient;
		this.tx = tx;
		this.props = props;
		this.executor = executor;
	}

	/** 재시작 전에 줄 서 있던 클립을 되살린다. */
	@EventListener(ApplicationReadyEvent.class)
	public void resumePending() {
		List<Long> pending = clipRepository.findProcessingIds();
		if (!pending.isEmpty()) {
			log.info("재시작 뒤 처리 대기 클립 {}건 다시 줄 세움", pending.size());
			pending.forEach(this::enqueue);
		}
	}

	/** 처리기에 줄 세운다(커밋 뒤에 부른다). */
	public void enqueue(Long clipId) {
		executor.execute(() -> {
			try {
				process(clipId);
			} catch (RuntimeException e) {
				if (Thread.currentThread().isInterrupted()) {
					return; // 종료 중: PROCESSING 으로 두고 재시작 때 다시
				}
				log.error("클립 처리 중 예외: clip={}", clipId, e);
				fail(clipId, ERROR);
			}
		});
	}

	/** 처리기가 시작할 때 읽는 값. LIVE 의 구간 시작은 방송 시작 + start_seconds 로 복원한다. */
	record Job(ClipSource source, String recordingFile, Long channelId, Instant liveStart, int startSeconds,
			int durationSeconds) {
	}

	private enum SaveResult {
		SAVED,
		DISCARDED
	}

	/** 처리 한 건(동기). */
	public void process(Long clipId) {
		Job job = tx.execute(s -> clipRepository.findForProcessing(clipId)
				.filter(Clip::isProcessable)
				.filter(c -> !discardIfBroadcastDeleted(c))
				.map(c -> new Job(c.getSource(), c.getRecording().getFilePath(), c.getChannel().getId(),
						c.getBroadcast().getStartedAt().plusSeconds(c.getStartSeconds()), c.getStartSeconds(),
						c.getDurationSeconds()))
				.orElse(null));
		if (job == null) {
			log.info("클립 처리 건너뜀(없음 · 삭제 · PROCESSING 아님): clip={}", clipId);
			return;
		}
		String outRelative = ClipFiles.PRIVATE_DIR + UUID.randomUUID() + ".mp4";
		Path out = files.resolve(outRelative);
		String thumbnailRelative = ClipFiles.thumbnailPathFor(clipId);
		boolean keep = false;
		boolean thumbnailMade = false;
		// 공개라 vod/clips/ 로 옮긴 뒤 DB 커밋이 실패하면 옮긴 파일도 지워야 한다(공개 폴더에 고아가 남지 않게)
		AtomicReference<String> moved = new AtomicReference<>();
		try {
			if (out == null) {
				log.warn("클립 출력 경로가 녹화 디렉터리 밖: clip={} {}", clipId, outRelative);
				fail(clipId, IO_ERROR);
				return;
			}
			Files.createDirectories(out.getParent());
			String failure = switch (job.source()) {
				case RECORDING -> cutRecording(job, out);
				case LIVE -> downloadLive(job, out);
			};
			if (Thread.currentThread().isInterrupted()) {
				log.info("클립 처리 중단(종료 중), 재시작 뒤 다시 시도: clip={}", clipId);
				return;
			}
			if (failure != null) {
				fail(clipId, failure);
				return;
			}
			if (!Files.isRegularFile(out) || Files.size(out) == 0) {
				fail(clipId, job.source() == ClipSource.LIVE ? NO_MEDIA : EMPTY);
				return;
			}
			Double probed = probeDurationSeconds(out);
			if (probed != null && Math.round(probed) <= 0) {
				fail(clipId, EMPTY);
				return;
			}
			Integer duration = probed == null ? null : (int) Math.round(probed);
			long size = Files.size(out);
			thumbnailMade = makeThumbnail(out, thumbnailRelative);
			String thumbnail = thumbnailMade ? thumbnailRelative : null;
			SaveResult saved = tx.execute(s -> {
				Clip c = clipRepository.findByIdForUpdate(clipId).orElse(null);
				if (c == null || !c.isProcessable() || discardIfBroadcastDeleted(c)) {
					return SaveResult.DISCARDED;
				}
				String finalRelative = outRelative;
				if (c.getVisibility() == Visibility.PUBLIC) {
					finalRelative = ClipFiles.videoPathFor(outRelative, Visibility.PUBLIC);
					try {
						files.moveVideo(outRelative, finalRelative);
					} catch (IOException e) {
						throw new UncheckedIOException("공개 폴더로 옮기기 실패", e);
					}
					moved.set(finalRelative);
				}
				c.markReady(finalRelative, duration, size, thumbnail);
				return SaveResult.SAVED;
			});
			if (saved == SaveResult.SAVED) {
				keep = true;
				log.info("클립 처리 완료: clip={} source={} file={} duration={}s size={}", clipId, job.source(),
						moved.get() != null ? moved.get() : outRelative, duration, size);
			} else {
				log.info("클립 결과 버림(그 사이 삭제됨): clip={}", clipId);
			}
		} catch (IOException e) {
			log.warn("클립 처리 파일 오류: clip={}", clipId, e);
			fail(clipId, IO_ERROR);
		} finally {
			if (!keep) {
				deleteQuietly(out); // 실패 · 버림 · 예외(DB 오류 포함) 모두 만든 파일을 지운다
				if (moved.get() != null) {
					files.deleteVideoQuietly(moved.get());
				}
				if (thumbnailMade) {
					files.deleteThumbnailQuietly(thumbnailRelative);
				}
			}
		}
	}

	/** @return 실패 사유, 성공이면 null */
	private String cutRecording(Job job, Path out) {
		String located = recordingFiles.locateVideo(job.recordingFile());
		Path src = located == null ? null : recordingFiles.resolve(located);
		if (src == null) {
			return SOURCE_FILE_MISSING;
		}
		FfmpegRunner.Result result = ffmpeg.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-nostdin", "-y",
				"-ss", String.valueOf(job.startSeconds()), "-i", src.toString(), "-t", String.valueOf(job.durationSeconds()),
				"-c", "copy", "-avoid_negative_ts", "make_zero", "-movflags", "+faststart", out.toString()),
				props.clip().timeout());
		if (result.success()) {
			return null;
		}
		log.warn("클립 자르기 실패: {} {}", src, result.describe());
		return CUT_FAILED;
	}

	/** @return 실패 사유, 성공이면 null */
	private String downloadLive(Job job, Path out) {
		try {
			boolean got = mediaMtxClient.downloadRecording("live/" + job.channelId(), job.liveStart(),
					Duration.ofSeconds(job.durationSeconds()), out);
			return got ? null : NO_MEDIA;
		} catch (RestClientException e) {
			log.warn("라이브 클립 받기 실패: channel={} ({})", job.channelId(), e.getMessage());
			return DOWNLOAD_FAILED;
		}
	}

	/** ffprobe 로 길이(초). 실패하면 null(요청 길이를 그대로 둔다). */
	private Double probeDurationSeconds(Path file) {
		FfmpegRunner.Result r = ffmpeg.ffprobe(List.of("-v", "error", "-show_entries", "format=duration",
				"-of", "default=noprint_wrappers=1:nokey=1", file.toString()), PROBE_TIMEOUT);
		if (!r.success()) {
			log.warn("클립 ffprobe 실패: {} ({})", file, r.describe());
			return null;
		}
		try {
			return Double.parseDouble(r.output().strip());
		} catch (NumberFormatException | NullPointerException e) {
			log.warn("클립 ffprobe 출력 해석 실패: {}", r.output());
			return null;
		}
	}

	/** 1초 지점 한 장을 tmp 로 뽑아 rename. @return 만들었으면 true */
	private boolean makeThumbnail(Path clip, String thumbnailRelative) {
		Path target = files.resolveUpload(thumbnailRelative);
		if (target == null) {
			return false;
		}
		Path temp = target.resolveSibling(target.getFileName().toString().replace(".jpg", ".tmp.jpg"));
		try {
			Files.createDirectories(target.getParent());
			FfmpegRunner.Result r = ffmpeg.ffmpeg(List.of("-ss", "1", "-i", clip.toString(), "-frames:v", "1",
					"-vf", "scale=640:-2", "-q:v", "4", "-y", temp.toString()), THUMBNAIL_TIMEOUT);
			if (!r.success() || !Files.isRegularFile(temp) || Files.size(temp) == 0) {
				log.info("클립 썸네일 실패(클립은 READY): {} {}", thumbnailRelative, r.describe());
				return false;
			}
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			return true;
		} catch (IOException e) {
			log.warn("클립 썸네일 저장 실패(클립은 READY): {} ({})", thumbnailRelative, e.toString());
			return false;
		} finally {
			deleteQuietly(temp);
		}
	}

	/**
	 * 클립 생성과 방송 삭제가 겹치면(생성이 "삭제 안 됨"을 읽은 뒤 삭제가 먼저 커밋) 지워진 방송에 클립이 남는다.
	 * 처리기가 시작할 때와 READY 저장 직전에 방송을 다시 보고, 삭제됐으면 클립도 soft delete 한다(결과는 버린다).
	 * @return 삭제했으면 true
	 */
	private boolean discardIfBroadcastDeleted(Clip c) {
		if (!c.getBroadcast().isDeleted()) {
			return false;
		}
		c.markDeleted(Instant.now());
		log.info("클립 버림(원본 방송이 삭제됨): clip={} broadcast={}", c.getId(), c.getBroadcast().getId());
		return true;
	}

	private void fail(Long clipId, String reason) {
		tx.executeWithoutResult(s -> clipRepository.findByIdForUpdate(clipId).ifPresent(c -> {
			if (c.isProcessable() && !discardIfBroadcastDeleted(c)) {
				c.markFailed(reason);
				log.warn("클립 처리 실패: clip={} reason={}", clipId, reason);
			}
		}));
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
