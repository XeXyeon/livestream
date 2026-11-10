package com.livestream.livestream.chat;

import com.livestream.livestream.broadcast.BroadcastStatus;
import java.time.Instant;

/**
 * 방송 토픽 {@code /topic/broadcasts/{id}} 이벤트 발행 (DESIGN §4.14). CHAT-1 이 STOMP 구현으로 바꾼다.
 * 상태 전이 서비스는 DB 커밋 뒤에 부른다.
 */
public interface BroadcastEventPublisher {

	/** {@code {type:"status", status, at}} */
	void status(Long broadcastId, BroadcastStatus status, Instant at);
}
