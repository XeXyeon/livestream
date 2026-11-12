package com.livestream.livestream.broadcast;

import com.livestream.livestream.channel.LiveBroadcastTerminator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 스트림 키 재발급 전 진행 중 방송 종료 (CH-1 → BC-1 연결). 재발급 트랜잭션에 참여하고,
 * kick 은 커밋 뒤에 실행되며 실패해도 로그만 남긴다(재발급을 막지 않는다).
 */
@Component
@RequiredArgsConstructor
public class BroadcastLiveTerminator implements LiveBroadcastTerminator {

	private final BroadcastLifecycleService lifecycle;

	@Override
	public void endLiveBroadcastOf(Long channelId) {
		lifecycle.endOngoingOf(channelId);
	}
}
