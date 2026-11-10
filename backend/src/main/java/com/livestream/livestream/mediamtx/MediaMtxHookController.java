package com.livestream.livestream.mediamtx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.recording.RecordingSegmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MediaMTX runOnReady · runOnNotReady · runOnRecordSegmentComplete 훅 (DESIGN §2.3 · §4.6 · §4.10). {@code X-Internal-Token} 필수(InternalTokenFilter).
 * 응답 코드는 MediaMTX 가 보지 않으므로, 해석할 수 없는 경로는 로그만 남기고 204.
 */
@Slf4j
@Tag(name = "Internal (MediaMTX)", description = "MediaMTX 인증 콜백 · 훅. 외부 비공개")
@RestController
@RequestMapping("/api/internal/mediamtx")
@RequiredArgsConstructor
public class MediaMtxHookController {

	private final BroadcastLifecycleService lifecycle;
	private final MediaMtxClient mediaMtxClient;
	private final RecordingSegmentService recordingSegmentService;

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ReadyHook(String path, String sourceType, String query) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record NotReadyHook(String path) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SegmentCompleteHook(String path, String segmentPath) {
	}

	@Operation(summary = "runOnReady 훅", description = "새 방송 LIVE 또는 유예 중 방송 재개")
	@PostMapping("/ready")
	public ResponseEntity<Void> ready(@RequestBody ReadyHook hook) {
		Long channelId = MediaMtxPaths.channelIdOf(hook.path());
		if (channelId == null) {
			log.warn("ready 훅: 해석할 수 없는 경로");
			return ResponseEntity.noContent().build();
		}
		BroadcastSource source = MediaMtxPaths.sourceOf(hook.sourceType(), hook.query());
		lifecycle.onReady(channelId, source);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "runOnNotReady 훅", description = "LIVE 방송을 DISCONNECTED로(유예 시작)")
	@PostMapping("/not-ready")
	public ResponseEntity<Void> notReady(@RequestBody NotReadyHook hook) {
		Long channelId = MediaMtxPaths.channelIdOf(hook.path());
		if (channelId == null) {
			log.warn("not-ready 훅: 해석할 수 없는 경로");
			return ResponseEntity.noContent().build();
		}
		if (stillPublishing(channelId)) {
			// 훅은 MediaMTX 가 프로세스로 따로 띄워 순서가 보장되지 않는다. overridePublisher 교체 · 빠른 재연결에서
			// 옛 연결의 not-ready 가 새 연결의 ready 보다 늦게 오면 송출 중인 방송이 DISCONNECTED → 60초 뒤 ENDED 가 된다.
			log.info("not-ready 훅 무시(송출자 있음): channel={}", channelId);
			return ResponseEntity.noContent().build();
		}
		lifecycle.onNotReady(channelId);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "runOnRecordSegmentComplete 훅", description = "채널의 현재 방송 recording 에 녹화 조각 추가")
	@PostMapping("/segment-complete")
	public ResponseEntity<Void> segmentComplete(@RequestBody SegmentCompleteHook hook) {
		Long channelId = MediaMtxPaths.channelIdOf(hook.path());
		if (channelId == null) {
			log.warn("segment-complete 훅: 해석할 수 없는 경로");
			return ResponseEntity.noContent().build();
		}
		recordingSegmentService.onSegmentComplete(channelId, hook.segmentPath());
		return ResponseEntity.noContent().build();
	}

	/** MediaMTX 조회가 실패하면 송출자가 없다고 보고 원래대로 DISCONNECTED 처리한다(유예 60초 안에 ready 가 오면 복구). */
	private boolean stillPublishing(Long channelId) {
		try {
			return mediaMtxClient.hasPublisher(MediaMtxPaths.pathOf(channelId));
		} catch (RuntimeException e) {
			log.warn("not-ready 훅: MediaMTX 경로 조회 실패, 끊김으로 처리: channel={} ({})", channelId, e.toString());
			return false;
		}
	}
}
