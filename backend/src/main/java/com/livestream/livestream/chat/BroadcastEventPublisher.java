package com.livestream.livestream.chat;

import com.livestream.livestream.broadcast.BroadcastStatus;
import com.livestream.livestream.channel.Category;
import com.livestream.livestream.chat.dto.DonationMessage;
import java.time.Instant;

/**
 * 방송 토픽 {@code /topic/broadcasts/{id}} 이벤트 발행 (DESIGN §4.14). CHAT-1 이 STOMP 구현으로 바꾼다.
 * 상태 전이 서비스는 DB 커밋 뒤에 부른다.
 */
public interface BroadcastEventPublisher {

	/** {@code {type:"status", status, at}} */
	void status(Long broadcastId, BroadcastStatus status, Instant at);

	/** 송출 중 바뀐 방송 정보 {@code {type:"info", title, category, at}} — 값이 실제로 바뀌었을 때만(FE-5#1). */
	void info(Long broadcastId, String title, Category category, Instant at);

	/** 승인된 후원: 채팅 버퍼(history)에 넣고 {@code {type:"donation", …}} 를 보낸다. */
	void donation(Long broadcastId, DonationMessage message);
}
