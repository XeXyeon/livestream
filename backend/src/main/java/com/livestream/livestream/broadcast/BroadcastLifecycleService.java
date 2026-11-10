package com.livestream.livestream.broadcast;

import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.chat.BroadcastEventPublisher;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.common.security.OwnershipGuard;
import com.livestream.livestream.common.tx.AfterCommit;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방송 상태 전이 (DESIGN §4.6): ready → LIVE, not-ready → DISCONNECTED, 유예 안 ready → LIVE, end · 유예 만료 → ENDED.
 * <ul>
 *   <li>같은 채널의 전이는 채널 행 잠금({@code findByIdForUpdate})으로 직렬화한다. 잠금 순서는 channel → broadcast.</li>
 *   <li>DB 밖의 부수 효과(publish 차단 · kick · 상태 이벤트 · 종료 이벤트)는 커밋 뒤에 실행하고, 실패는 로그만 남긴다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastLifecycleService {

	public enum EndReason {
		/** 소유자 종료 API · 스트림 키 재발급 */
		OWNER,
		/** DISCONNECTED 유예 경과 */
		GRACE_TIMEOUT
	}

	private final ChannelRepository channelRepository;
	private final BroadcastRepository broadcastRepository;
	private final RecordingRepository recordingRepository;
	private final PublishBlockRegistry publishBlockRegistry;
	private final MediaMtxClient mediaMtxClient;
	private final BroadcastEventPublisher eventPublisher;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final AppProperties props;

	// ---------- 인증 콜백 (§4.9) ----------

	/** publish 허용: 채널이 있고, 종료 뒤 차단 중이 아니고, 스트림 키가 일치. */
	@Transactional(readOnly = true)
	public boolean canPublish(Long channelId, String streamKey) {
		if (streamKey == null || streamKey.isEmpty()) {
			return false;
		}
		if (publishBlockRegistry.isBlocked(channelId)) {
			log.info("publish 거절(종료 뒤 차단 중): channel={}", channelId);
			return false;
		}
		return channelRepository.findById(channelId)
				.map(c -> MessageDigest.isEqual(c.getStreamKey().getBytes(StandardCharsets.UTF_8),
						streamKey.getBytes(StandardCharsets.UTF_8)))
				.orElse(false);
	}

	// ---------- MediaMTX 훅 ----------

	/**
	 * ready 훅: 진행 중 방송이 있으면 LIVE로 되돌리고 source 갱신(유예 재연결 · overridePublisher 교체),
	 * 없으면 새 방송(제목 · 카테고리는 채널에서 복사) + recording(RECORDING) 생성.
	 */
	@Transactional
	public void onReady(Long channelId, BroadcastSource source) {
		Optional<Channel> locked = channelRepository.findByIdForUpdate(channelId);
		if (locked.isEmpty()) {
			log.warn("ready 훅: 없는 채널 {}", channelId);
			return;
		}
		Channel channel = locked.get();
		Optional<Broadcast> ongoing = broadcastRepository.findOngoing(channelId);
		if (ongoing.isPresent()) {
			Broadcast b = ongoing.get();
			boolean wasDisconnected = b.getStatus() == BroadcastStatus.DISCONNECTED;
			b.resume(source);
			log.info("방송 재개: broadcast={} channel={} source={} (이전 {})", b.getId(), channelId, source,
					wasDisconnected ? "DISCONNECTED" : "LIVE");
			if (wasDisconnected) {
				publishStatusAfterCommit(b.getId(), BroadcastStatus.LIVE, Instant.now());
			}
			return;
		}
		if (publishBlockRegistry.isBlocked(channelId)) {
			// 종료 직후 늦게 도착한 ready 훅(종료 전 연결의 것). 인증 콜백이 차단 중 publish 를 거절하므로 정상 송출은 여기 오지 않는다.
			log.info("ready 훅 무시(종료 뒤 차단 중): channel={}", channelId);
			return;
		}
		Instant now = Instant.now();
		Broadcast b = broadcastRepository.save(Broadcast.start(channel, source, now));
		recordingRepository.save(Recording.startFor(b, channel.isAutoPublishRecording()));
		log.info("방송 시작: broadcast={} channel={} source={}", b.getId(), channelId, source);
		publishStatusAfterCommit(b.getId(), BroadcastStatus.LIVE, now);
	}

	/**
	 * not-ready 훅: LIVE면 DISCONNECTED + disconnected_at. 그 외 상태는 무시(멱등).
	 * 훅 컨트롤러가 먼저 MediaMTX 에 송출자가 아직 있는지 확인해, 늦게 온 옛 연결의 훅은 여기까지 오지 않게 한다.
	 */
	@Transactional
	public void onNotReady(Long channelId) {
		if (channelRepository.findByIdForUpdate(channelId).isEmpty()) {
			log.warn("not-ready 훅: 없는 채널 {}", channelId);
			return;
		}
		broadcastRepository.findOngoing(channelId)
				.filter(b -> b.getStatus() == BroadcastStatus.LIVE)
				.ifPresent(b -> {
					Instant now = Instant.now();
					b.disconnect(now);
					log.info("송출 끊김: broadcast={} channel={}", b.getId(), channelId);
					publishStatusAfterCommit(b.getId(), BroadcastStatus.DISCONNECTED, now);
				});
	}

	// ---------- 종료 ----------

	/**
	 * 소유자 종료 API ({@code POST /broadcasts/{id}/end}). 다른 회원이면 403, ENDED면 409 BROADCAST_ALREADY_ENDED.
	 */
	@Transactional
	public void endByOwner(Long broadcastId, MemberPrincipal me) {
		Long channelId = broadcastRepository.findChannelIdOf(broadcastId)
				.orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
		Channel channel = channelRepository.findByIdForUpdate(channelId)
				.orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
		OwnershipGuard.requireChannelOwner(channel, me);
		Broadcast b = reloadActive(broadcastId);
		if (b.getStatus() == BroadcastStatus.ENDED) {
			throw new ApiException(ErrorCode.BROADCAST_ALREADY_ENDED);
		}
		end(b, EndReason.OWNER);
	}

	/** 채널의 진행 중 방송을 종료(스트림 키 재발급). 없으면 아무 일도 하지 않는다. 호출 쪽 트랜잭션에 참여한다. */
	@Transactional
	public void endOngoingOf(Long channelId) {
		channelRepository.findByIdForUpdate(channelId)
				.flatMap(c -> broadcastRepository.findOngoing(channelId))
				.ifPresent(b -> end(b, EndReason.OWNER));
	}

	/** 유예 만료 종료(스케줄러가 방송마다 부른다). 잠근 뒤 다시 확인해, 그 사이 재연결됐으면 그대로 둔다. */
	@Transactional
	public void endIfGraceExpired(Long broadcastId) {
		Optional<Long> channelId = broadcastRepository.findChannelIdOf(broadcastId);
		if (channelId.isEmpty() || channelRepository.findByIdForUpdate(channelId.get()).isEmpty()) {
			return;
		}
		Broadcast b = reloadActive(broadcastId);
		Instant deadline = Instant.now().minus(graceDuration());
		if (b.getStatus() == BroadcastStatus.DISCONNECTED && b.getDisconnectedAt().isBefore(deadline)) {
			end(b, EndReason.GRACE_TIMEOUT);
		}
	}

	public Duration graceDuration() {
		return Duration.ofSeconds(props.broadcast().disconnectGraceSeconds());
	}

	/**
	 * end 공통 (§4.6): ENDED + ended_at, recording → PROCESSING. 커밋 뒤: publish 차단 → (LIVE였으면) kick →
	 * status(ended) 이벤트 → {@link BroadcastEndedEvent}(녹화 병합 · 채팅 버퍼 정리).
	 * 차단을 kick 보다 먼저 걸어, kick 당한 송출자의 자동 재연결이 인증 콜백에서 거절되게 한다.
	 */
	/** 호출 쪽이 채널 · 방송 행을 잠근 뒤 부른다(BroadcastService 삭제도 쓴다). */
	void end(Broadcast b, EndReason reason) {
		boolean wasLive = b.getStatus() == BroadcastStatus.LIVE;
		Instant now = Instant.now();
		b.end(now);
		Long recordingId = recordingRepository.findByBroadcastId(b.getId())
				.map(r -> {
					r.markProcessing();
					return r.getId();
				})
				.orElse(null);
		Long broadcastId = b.getId();
		Long channelId = b.getChannel().getId();
		log.info("방송 종료: broadcast={} channel={} reason={}", broadcastId, channelId, reason);

		afterCommit("publish 차단", () -> publishBlockRegistry.block(channelId));
		if (wasLive) {
			afterCommit("kick", () -> mediaMtxClient.kickPublisher("live/" + channelId));
		}
		publishStatusAfterCommit(broadcastId, BroadcastStatus.ENDED, now);
		afterCommit("종료 이벤트", () -> applicationEventPublisher.publishEvent(
				new BroadcastEndedEvent(broadcastId, channelId, recordingId)));
	}

	/**
	 * 채널 잠금 뒤 방송을 잠금 읽기로 가져온다. 앞선 일반 SELECT 가 MySQL RR 스냅숏을 만들었을 수 있어서,
	 * 잠금을 기다리는 동안 다른 트랜잭션이 커밋한 상태를 놓치지 않으려면 잠금 읽기(최신 커밋 값)가 필요하다.
	 */
	private Broadcast reloadActive(Long broadcastId) {
		return broadcastRepository.findActiveForUpdate(broadcastId)
				.orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
	}

	private void publishStatusAfterCommit(Long broadcastId, BroadcastStatus status, Instant at) {
		afterCommit("상태 이벤트", () -> eventPublisher.status(broadcastId, status, at));
	}

	private static void afterCommit(String what, Runnable action) {
		AfterCommit.run(what, action);
	}
}
