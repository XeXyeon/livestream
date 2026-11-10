package com.livestream.livestream.thumbnail;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code app.thumbnail.interval-seconds} 마다 LIVE 방송의 썸네일을 캡처한다 (DESIGN §4.11).
 * fixedDelay 라 이전 회차가 끝나기 전에는 겹쳐 돌지 않는다. 방송별 실패는 {@link ThumbnailService} 가 로그만 남긴다.
 */
@Component
@RequiredArgsConstructor
public class ThumbnailCaptureScheduler {

	private final ThumbnailService thumbnailService;

	@Scheduled(fixedDelayString = "#{${app.thumbnail.interval-seconds} * 1000}",
			initialDelayString = "#{${app.thumbnail.interval-seconds} * 1000}")
	public void captureAll() {
		thumbnailService.captureAllLive();
	}
}
