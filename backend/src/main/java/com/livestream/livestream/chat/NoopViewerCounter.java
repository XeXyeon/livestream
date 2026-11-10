package com.livestream.livestream.chat;

import org.springframework.stereotype.Component;

/** CHAT-1 전 임시 구현: 항상 0. CHAT-1 이 실구현을 만들면 이 클래스를 지운다. */
@Component
public class NoopViewerCounter implements ViewerCounter {

	@Override
	public long count(Long broadcastId) {
		return 0;
	}
}
