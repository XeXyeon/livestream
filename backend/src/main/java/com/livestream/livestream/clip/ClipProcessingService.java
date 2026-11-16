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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * 클립 처리기 (DESIGN §4.18). {@code clipExecutor}(단일 스레드)에서 하나씩 돈다.
 * <ul>
 *   <li>시작할 때 클립을 다시 읽어 PROCESSING 이 아니거나 삭제됐으면 아무것도 하지 않는다.</li>
 *   <li>RECORDING: 병합된 다시보기 파일을 {@code ffmpeg -ss -i -t -c copy} 로 자른다(재인코딩 없음, 시작은 직전 키프레임으로 당겨질 수 있다).</li>
 *   <li>LIVE: 라이브 클립 초안(CLIP-3) 파일을 RECORDING 과 같은 방식으로 자른다(시작은 초안 기준). 초안 파일이 없으면 FAILED(SOURCE_FILE_MISSING).</li>
 *   <li>초안 받기({@link #enqueueDraft}): MediaMTX 재생 서버에서 창 {@code [started_at + window_start_seconds, +window_seconds)} mp4 를
 *       {@code private/clip-drafts/} 에 받고 ffprobe 실제 길이로 창 길이를 맞춰 READY. 404 · 빈 응답 · 받은 길이가 최소 클립 길이보다 짧으면 FAILED(NO_MEDIA).</li>
 *   <li>결과는 항상 {@code private/clips/} 에 먼저 쓴다. ffprobe 길이가 0 이면 FAILED(EMPTY). 썸네일은 실패해도 READY(썸네일만 null).</li>
 *   <li>새 트랜잭션에서 클립을 잠그고 PROCESSING · 삭제 안 됨일 때만 READY. 그때의 visibility 가 PUBLIC 이면 {@code vod/clips/} 로
 *       옮긴 뒤 경로를 적는다. 그 사이 클립이나 원본 방송이 삭제됐으면 만든 파일을 지운다. 실패는 FAILED + 사유 코드.</li>
 *   <li>서버가 재시작되면 PROCESSING 클립과 PENDING 초안을 다시 줄 세운다(초안은 저장된 창으로 같은 범위를 받는다).</li>
 *   <li>줄 서 있거나 처리 중인 클립 · 초안은 {@link #inFlight} · {@link #draftsInFlight} 에 있다. 1분마다 집합에 없는 오래된
 *       PROCESSING 클립 · PENDING 초안을 FAILED(TIMEOUT)로 정리한다(갇힌 클립 정리, CLIP-1#1). 단일 인스턴스 전제.</li>
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
	static final String TIMEOUT = "TIMEOUT";

	private final ClipRepository clipRepository;
	private final ClipDraftRepository draftRepository;
	private final ClipFiles files;
	private final RecordingFiles recordingFiles;
	private final FfmpegRunner ffmpeg;
	private final MediaMtxClient mediaMtxClient;
	private final TransactionTemplate tx;
	private final AppProperties props;
	private final TaskExecutor executor;
	/** 줄 서 있거나 처리 중인 clipId. 여기 없는 오래된 PROCESSING 은 갇힌 것으로 본다({@link #failStale(Instant)}). */
	private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
	/** 줄 서 있거나 받는 중인 초안 id. 클립 id 와 번호가 겹치므로 집합을 나눈다. */
	private final Set<Long> draftsInFlight = ConcurrentHashMap.newKeySet();
	/**
	 * 재시작 복구({@link #resumePending})가 끝났는가. 스케줄러는 ApplicationReadyEvent 전에 첫 실행을 하므로,
	 * 그 전에 정리하면 집합이 비어 되살릴 클립까지 TIMEOUT 이 된다.
	 */
	private volatile boolean resumed;
	/** 종료 중이면 중단된 작업이 집합에서 빠져도 정리하지 않는다(재시작 때 다시 줄 세운다). */
	private volatile boolean shuttingDown;

	public ClipProcessingService(ClipRepository clipRepository, ClipDraftRepository draftRepository, ClipFiles files,
			RecordingFiles recordingFiles, FfmpegRunner ffmpeg, MediaMtxClient mediaMtxClient, TransactionTemplate tx,
			AppProperties props, @Qualifier(AsyncConfig.CLIP_EXECUTOR) TaskExecutor executor) {
		this.clipRepository = clipRepository;
		this.draftRepository = draftRepository;
		this.files = files;
		this.recordingFiles = recordingFiles;
		this.ffmpeg = ffmpeg;
		this.mediaMtxClient = mediaMtxClient;
		this.tx = tx;
		this.props = props;
		this.executor = executor;
	}

	/** 재시작 전에 줄 서 있던 초안 · 클립을 되살린다. */
	@EventListener(ApplicationReadyEvent.class)
	public void resumePending() {
		try {
			List<Long> drafts = draftRepository.findPendingIds();
			if (!drafts.isEmpty()) {
				log.info("재시작 뒤 받기 대기 클립 초안 {}건 다시 줄 세움", drafts.size());
				drafts.forEach(this::enqueueDraft);
			}
			List<Long> pending = clipRepository.findProcessingIds();
			if (!pending.isEmpty()) {
				log.info("재시작 뒤 처리 대기 클립 {}건 다시 줄 세움", pending.size());
				pending.forEach(this::enqueue);
			}
		} finally {
			resumed = true;
		}
	}

	@EventListener(ContextClosedEvent.class)
	public void onShutdown() {
		shuttingDown = true;
	}

	/** 처리기에 줄 세운다(커밋 뒤에 부른다). */
	public void enqueue(Long clipId) {
		inFlight.add(clipId);
		try {
			executor.execute(() -> {
				try {
					process(clipId);
				} catch (RuntimeException e) {
					if (Thread.currentThread().isInterrupted()) {
						return; // 종료 중: PROCESSING 으로 두고 재시작 때 다시
					}
					log.error("클립 처리 중 예외: clip={}", clipId, e);
					fail(clipId, ERROR);
				} finally {
					inFlight.remove(clipId);
				}
			});
		} catch (RejectedExecutionException e) {
			// 종료 중 등으로 실행기가 거절: PROCESSING 으로 두고 재시작 때 resumePending 이 다시 줄 세운다
			inFlight.remove(clipId);
			log.warn("클립 처리 줄 세우기 거절됨(재시작 때 다시): clip={}", clipId);
		}
	}

	/** 초안 받기를 줄 세운다(커밋 뒤에 부른다). 클립과 같은 실행기 · 같은 갇힘 정리 규칙. */
	public void enqueueDraft(Long draftId) {
		draftsInFlight.add(draftId);
		try {
			executor.execute(() -> {
				try {
					processDraft(draftId);
				} catch (RuntimeException e) {
					if (Thread.currentThread().isInterrupted()) {
						return; // 종료 중: PENDING 으로 두고 재시작 때 다시
					}
					log.error("클립 초안 받기 중 예외: draft={}", draftId, e);
					failDraft(draftId, ERROR);
				} finally {
					draftsInFlight.remove(draftId);
				}
			});
		} catch (RejectedExecutionException e) {
			draftsInFlight.remove(draftId);
			log.warn("클립 초안 줄 세우기 거절됨(재시작 때 다시): draft={}", draftId);
		}
	}

	@Scheduled(fixedDelay = 60_000)
	public void failStale() {
		int failed = failStale(Instant.now());
		if (failed > 0) {
			log.warn("갇힌 클립 {}건을 FAILED(TIMEOUT)로 정리", failed);
		}
	}

	/**
	 * {@code PROCESSING AND created_at < now − app.clip.timeout} 가운데 {@link #inFlight} 에 없는 것을 FAILED(TIMEOUT)로.
	 * 집합에 있는 것은 큐에서 오래 기다리는 정상 클립이라 건드리지 않는다. 유예는 커밋과 enqueue 사이의 틈을 덮는다.
	 * @param now 기준 시각(테스트에서 주입)
	 * PENDING 초안도 같은 규칙으로 FAILED(TIMEOUT)로 바꾼다(CLIP-3).
	 * @return 정리한 건수(클립 · 초안, 방송 삭제로 버린 것 포함)
	 */
	public int failStale(Instant now) {
		if (!resumed || shuttingDown) {
			return 0;
		}
		Instant before = now.minus(props.clip().timeout());
		int failed = 0;
		for (Long id : clipRepository.findProcessingCreatedBefore(before)) {
			if (inFlight.contains(id)) {
				continue;
			}
			Boolean done = tx.execute(s -> clipRepository.findByIdForUpdate(id)
					.filter(c -> c.isProcessable() && c.getCreatedAt().isBefore(before) && !inFlight.contains(id))
					.map(c -> {
						if (!discardIfBroadcastDeleted(c)) {
							c.markFailed(TIMEOUT);
							log.warn("갇힌 클립 정리: clip={} createdAt={}", id, c.getCreatedAt());
						}
						return true;
					}).orElse(false));
			if (Boolean.TRUE.equals(done)) {
				failed++;
			}
		}
		for (Long id : draftRepository.findPendingCreatedBefore(before)) {
			if (draftsInFlight.contains(id)) {
				continue;
			}
			Boolean done = tx.execute(s -> draftRepository.findByIdForUpdate(id)
					.filter(d -> d.isPending() && d.getCreatedAt().isBefore(before) && !draftsInFlight.contains(id))
					.map(d -> {
						d.markFailed(TIMEOUT);
						log.warn("갇힌 클립 초안 정리: draft={} createdAt={}", id, d.getCreatedAt());
						return true;
					}).orElse(false));
			if (Boolean.TRUE.equals(done)) {
				failed++;
			}
		}
		return failed;
	}

	/**
	 * 처리기가 시작할 때 읽는 값. {@code startSeconds} 는 자를 원본 기준: RECORDING 은 다시보기 기준, LIVE 는 초안 기준
	 * ({@code clip.start_seconds − draft.window_start_seconds}). LIVE 의 초안이 지워졌으면 {@code draftFile} 이 null.
	 */
	record Job(ClipSource source, String recordingFile, String draftFile, int startSeconds, int durationSeconds) {
	}

	/** 초안 받기가 시작할 때 읽는 값: 방송 시작 시각과 창(방송 기준 시작 초 · 길이). */
	record DraftJob(Long channelId, Instant startedAt, DraftWindow window) {
	}

	/** 초안 창: 방송 시작 기준 시작 초와 길이(초). */
	record DraftWindow(int startSeconds, int seconds) {
	}

	/** 조각이 이어진다고 볼 끝 · 시작 차이. MediaMTX {@code /get} 이 합쳐 주는 기준보다 작게 잡는다(크면 못 받는 창을 남긴다). */
	static final Duration SEGMENT_CONTIGUITY_TOLERANCE = Duration.ofMillis(500);

	/**
	 * 창을 녹화 조각에 맞춘다(DESIGN §4.18 ①, CLIP-3#1). 창과 겹치는 조각 가운데 창 끝을 덮는(없으면 겹치는 마지막) 조각에서
	 * 거꾸로, 틈 · 겹침 없이 이어지는 조각만 묶는다. 그 첫 조각의 시작이 창 시작보다 늦으면 창 시작을 거기(초 올림)로 옮기고 끝은 그대로
	 * 둔다. 앞 조각은 버린다. 조각이 없으면 그대로.
	 */
	static DraftWindow alignToSegments(Instant startedAt, DraftWindow window, List<MediaMtxClient.RecordingSegment> segments) {
		Instant windowStart = startedAt.plusSeconds(window.startSeconds());
		Instant windowEnd = windowStart.plusSeconds(window.seconds());
		List<MediaMtxClient.RecordingSegment> overlapping = segments.stream()
				.filter(s -> s.start().isBefore(windowEnd) && s.end().isAfter(windowStart))
				.sorted(Comparator.comparing(MediaMtxClient.RecordingSegment::start))
				.toList();
		if (overlapping.isEmpty()) {
			return window;
		}
		int first = overlapping.size() - 1;
		while (first > 0) {
			Duration diff = Duration.between(overlapping.get(first - 1).end(), overlapping.get(first).start()).abs();
			if (diff.compareTo(SEGMENT_CONTIGUITY_TOLERANCE) > 0) {
				break;
			}
			first--;
		}
		Instant groupStart = overlapping.get(first).start();
		if (!groupStart.isAfter(windowStart)) {
			return window;
		}
		long millis = Duration.between(startedAt, groupStart).toMillis();
		int newStart = (int) Math.ceilDiv(millis, 1000L);
		int end = window.startSeconds() + window.seconds();
		return new DraftWindow(newStart, Math.max(0, end - newStart));
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
				.map(ClipProcessingService::jobOf)
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
				case LIVE -> cutDraft(job, out);
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
				fail(clipId, EMPTY);
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

	private static Job jobOf(Clip c) {
		ClipDraft draft = c.getDraft();
		if (c.getSource() == ClipSource.LIVE) {
			// 초안이 보관 정리로 지워졌으면(draft_id null) 자를 원본이 없다 → SOURCE_FILE_MISSING
			return new Job(ClipSource.LIVE, null, draft == null ? null : draft.getFilePath(),
					draft == null ? c.getStartSeconds() : c.getStartSeconds() - draft.getWindowStartSeconds(),
					c.getDurationSeconds());
		}
		return new Job(c.getSource(), c.getRecording().getFilePath(), null, c.getStartSeconds(), c.getDurationSeconds());
	}

	/** @return 실패 사유, 성공이면 null */
	private String cutRecording(Job job, Path out) {
		String located = recordingFiles.locateVideo(job.recordingFile());
		Path src = located == null ? null : recordingFiles.resolve(located);
		if (src == null) {
			return SOURCE_FILE_MISSING;
		}
		return cut(src, job, out);
	}

	/** LIVE: 초안 파일에서 초안 기준 구간을 자른다. @return 실패 사유, 성공이면 null */
	private String cutDraft(Job job, Path out) {
		Path src = job.draftFile() == null ? null : recordingFiles.resolve(job.draftFile());
		if (src == null || !Files.isRegularFile(src) || job.startSeconds() < 0) {
			return SOURCE_FILE_MISSING;
		}
		return cut(src, job, out);
	}

	/** {@code ffmpeg -ss -i -t -c copy}(재인코딩 없음). @return 실패 사유, 성공이면 null */
	private String cut(Path src, Job job, Path out) {
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

	/**
	 * 초안 받기 한 건(동기, CLIP-3). 재생 서버에서 창을 {@code private/clip-drafts/<uuid>.mp4} 로 받고 ffprobe 로 확인한 뒤,
	 * 새 트랜잭션에서 잠가 PENDING 일 때만 READY + 실제 길이. 그 사이 초안이 지워졌으면(새 초안 · 방송 삭제 · 보관 정리) 파일을 버린다.
	 */
	public void processDraft(Long draftId) {
		DraftJob job = tx.execute(s -> draftRepository.findForProcessing(draftId)
				.filter(ClipDraft::isPending)
				.filter(d -> {
					if (!d.getBroadcast().isDeleted()) {
						return true;
					}
					// 생성과 방송 삭제가 겹쳐 지워진 방송에 남은 초안: 행을 지운다(파일은 아직 없다)
					draftRepository.delete(d);
					log.info("클립 초안 버림(원본 방송이 삭제됨): draft={}", draftId);
					return false;
				})
				.map(d -> new DraftJob(d.getChannel().getId(), d.getBroadcast().getStartedAt(),
						new DraftWindow(d.getWindowStartSeconds(), d.getWindowSeconds())))
				.orElse(null));
		if (job == null) {
			log.info("클립 초안 받기 건너뜀(없음 · PENDING 아님): draft={}", draftId);
			return;
		}
		String outRelative = ClipFiles.DRAFT_DIR + UUID.randomUUID() + ".mp4";
		Path out = files.resolve(outRelative);
		boolean keep = false;
		try {
			if (out == null) {
				failDraft(draftId, IO_ERROR);
				return;
			}
			Files.createDirectories(out.getParent());
			String livePath = "live/" + job.channelId();
			DraftWindow window = alignedWindow(draftId, livePath, job);
			if (window.seconds() < props.clip().minSeconds()) {
				failDraft(draftId, NO_MEDIA);
				return;
			}
			String failure;
			try {
				boolean got = mediaMtxClient.downloadRecording(livePath, job.startedAt().plusSeconds(window.startSeconds()),
						Duration.ofSeconds(window.seconds()), out);
				failure = got ? null : NO_MEDIA;
			} catch (RestClientException e) {
				log.warn("클립 초안 받기 실패: draft={} channel={} ({})", draftId, job.channelId(), e.getMessage());
				failure = DOWNLOAD_FAILED;
			}
			if (Thread.currentThread().isInterrupted()) {
				log.info("클립 초안 받기 중단(종료 중), 재시작 뒤 다시 시도: draft={}", draftId);
				return;
			}
			if (failure == null && (!Files.isRegularFile(out) || Files.size(out) == 0)) {
				failure = NO_MEDIA;
			}
			// 끝의 part 가 아직 안 내려와 창보다 짧을 수 있다. 내림해 프론트의 범위 검증(start + duration ≤ windowSeconds)이
			// 실제 파일 안에 들게 한다. ffprobe 가 실패하면 요청한 창 길이를 그대로 둔다.
			Double probed = failure == null ? probeDurationSeconds(out) : null;
			int windowSeconds = probed == null ? window.seconds() : (int) Math.min(window.seconds(), Math.floor(probed));
			// 쓸 수 있는 클립(최소 길이)이 하나도 나오지 않는 초안은 READY 로 두지 않는다.
			if (failure == null && windowSeconds < props.clip().minSeconds()) {
				failure = NO_MEDIA;
			}
			if (failure != null) {
				failDraft(draftId, failure);
				return;
			}
			Boolean saved = tx.execute(s -> draftRepository.findByIdForUpdate(draftId)
					.filter(ClipDraft::isPending)
					.map(d -> {
						d.markReady(outRelative, window.startSeconds(), windowSeconds);
						return true;
					}).orElse(false));
			if (Boolean.TRUE.equals(saved)) {
				keep = true;
				log.info("클립 초안 받기 완료: draft={} file={} windowStart={}s window={}s", draftId, outRelative,
						window.startSeconds(), windowSeconds);
			} else {
				log.info("클립 초안 결과 버림(그 사이 지워짐): draft={}", draftId);
			}
		} catch (IOException e) {
			log.warn("클립 초안 파일 오류: draft={}", draftId, e);
			failDraft(draftId, IO_ERROR);
		} finally {
			if (!keep) {
				deleteQuietly(out);
			}
		}
	}

	/** 받기 전에 {@code /list} 로 창을 조각에 맞춘다. 실패하거나 비어 있으면 그대로(지금 동작). */
	private DraftWindow alignedWindow(Long draftId, String livePath, DraftJob job) {
		DraftWindow window = job.window();
		Instant start = job.startedAt().plusSeconds(window.startSeconds());
		try {
			DraftWindow aligned = alignToSegments(job.startedAt(), window,
					mediaMtxClient.listSegments(livePath, start, start.plusSeconds(window.seconds())));
			if (!aligned.equals(window)) {
				log.info("클립 초안 창을 녹화 조각에 맞춤: draft={} {}s+{}s → {}s+{}s", draftId, window.startSeconds(),
						window.seconds(), aligned.startSeconds(), aligned.seconds());
			}
			return aligned;
		} catch (RestClientException e) {
			log.warn("재생 서버 /list 실패, 창을 그대로 받음: draft={} ({})", draftId, e.getMessage());
			return window;
		}
	}

	private void failDraft(Long draftId, String reason) {
		tx.executeWithoutResult(s -> draftRepository.findByIdForUpdate(draftId).ifPresent(d -> {
			if (d.isPending()) {
				d.markFailed(reason);
				log.warn("클립 초안 받기 실패: draft={} reason={}", draftId, reason);
			}
		}));
	}

	/** ffprobe 로 길이(초). 실패하면 null(요청 길이를 그대로 둔다). */
	private Double probeDurationSeconds(Path file) {
		FfmpegRunner.Result r = ffmpeg.ffprobe(List.of("-v", "error", "-show_entries", "format=duration",
				"-of", "default=noprint_wrappers=1:nokey=1", file.toString()), PROBE_TIMEOUT);
		if (!r.success()) {
			log.warn("클립 ffprobe 실패: {} ({})", file, r.describe());
			return null;
		}
		Double seconds = r.durationSeconds();
		if (seconds == null) {
			log.warn("클립 ffprobe 출력 해석 실패: {}", r.output());
		}
		return seconds;
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
