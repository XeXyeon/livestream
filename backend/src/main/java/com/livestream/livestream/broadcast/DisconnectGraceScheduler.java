package com.livestream.livestream.broadcast;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 10초마다 유예가 지난 DISCONNECTED 방송을 ENDED로 (DESIGN §4.6). 방송마다 따로 트랜잭션을 연다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisconnectGraceScheduler {

	private final BroadcastRepository broadcastRepository;
	private final BroadcastLifecycleService lifecycle;

	@Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
	public void endExpired() {
		Instant deadline = Instant.now().minus(lifecycle.graceDuration());
		for (Long id : broadcastRepository.findDisconnectedBefore(deadline)) {
			try {
				lifecycle.endIfGraceExpired(id);
			} catch (RuntimeException e) {
				log.warn("유예 종료 실패: broadcast={}", id, e);
			}
		}
	}
}
