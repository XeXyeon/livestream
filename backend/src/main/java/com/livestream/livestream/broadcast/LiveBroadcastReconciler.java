package com.livestream.livestream.broadcast;

import com.livestream.livestream.mediamtx.MediaMtxClient;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * LIVE 방송을 MediaMTX 의 실제 송출(ready 경로)과 대조한다 (DESIGN §4.6, BC-1#1).
 * MediaMTX · 백엔드가 송출 중에 꺼지면 not-ready 훅이 오지 않아 방송이 LIVE 로 남는다.
 * 송출이 없는 LIVE 방송은 not-ready 훅과 같은 경로({@link BroadcastLifecycleService#onNotReady})로 DISCONNECTED 로 보내
 * 기존 유예(60초) → ENDED({@link DisconnectGraceScheduler}) 를 그대로 탄다. 유예 타이머는 DB 의 disconnected_at 기준이라 재시작에도 유지된다.
 * <p>
 * MediaMTX 호출이 실패하면 아무것도 바꾸지 않고 다음 주기를 기다린다(실패 ≠ 송출 없음).
 * 시작 직후 첫 대조는 initialDelay 뒤에 돌고, MediaMTX 가 아직 안 떴으면 다음 주기에 다시 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveBroadcastReconciler {

	private final BroadcastRepository broadcastRepository;
	private final BroadcastLifecycleService lifecycle;
	private final MediaMtxClient mediaMtxClient;

	@Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
	public void reconcile() {
		var liveRefs = broadcastRepository.findLiveRefs();
		if (liveRefs.isEmpty()) {
			return;
		}
		Set<String> readyPaths;
		try {
			readyPaths = mediaMtxClient.listReadyPaths();
		} catch (RuntimeException e) {
			log.warn("방송 대조 건너뜀: MediaMTX 경로 목록 조회 실패 ({})", e.toString());
			return;
		}
		for (LiveBroadcastRef ref : liveRefs) {
			String path = "live/" + ref.channelId();
			if (readyPaths.contains(path)) {
				continue;
			}
			try {
				// 목록을 읽은 뒤 막 시작한 방송을 끊지 않도록, 후보는 한 번 더 개별 확인한다(not-ready 훅과 같은 확인).
				if (mediaMtxClient.hasPublisher(path)) {
					continue;
				}
				log.info("송출 없는 LIVE 방송 발견: broadcast={} channel={}", ref.broadcastId(), ref.channelId());
				lifecycle.onNotReady(ref.channelId());
			} catch (RuntimeException e) {
				log.warn("방송 대조 실패: broadcast={} channel={} ({})", ref.broadcastId(), ref.channelId(), e.toString());
			}
		}
	}
}
