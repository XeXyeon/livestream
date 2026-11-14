package com.livestream.livestream.chat;

/** 방송별 동시 시청자 수 (DESIGN §4.14). CHAT-1 이 STOMP 구독 세션 수로 구현한다. */
public interface ViewerCounter {

	long count(Long broadcastId);
}
