package com.livestream.livestream.chat;

import com.livestream.livestream.broadcast.BroadcastStatus;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** CHAT-1 전 임시 구현: 로그만 남긴다. CHAT-1 이 STOMP 구현을 만들면 이 클래스를 지운다. */
@Slf4j
@Component
public class LoggingBroadcastEventPublisher implements BroadcastEventPublisher {

	@Override
	public void status(Long broadcastId, BroadcastStatus status, Instant at) {
		log.info("[event] broadcast={} status={} at={}", broadcastId, status, at);
	}
}
