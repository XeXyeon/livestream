package com.livestream.livestream.chat;

import com.livestream.livestream.broadcast.BroadcastStatus;
import com.livestream.livestream.chat.dto.StatusMessage;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** {@link BroadcastEventPublisher} 의 STOMP 구현: {@code /topic/broadcasts/{id}} 로 보낸다. */
@Component
@RequiredArgsConstructor
public class StompBroadcastEventPublisher implements BroadcastEventPublisher {

	private final SimpMessagingTemplate messagingTemplate;

	@Override
	public void status(Long broadcastId, BroadcastStatus status, Instant at) {
		messagingTemplate.convertAndSend(StompDestinations.broadcastTopic(broadcastId), StatusMessage.of(status, at));
	}
}
