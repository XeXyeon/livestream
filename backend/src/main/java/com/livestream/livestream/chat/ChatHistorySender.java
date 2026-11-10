package com.livestream.livestream.chat;

import com.livestream.livestream.chat.dto.HistoryMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * 방송 토픽을 구독한 세션에만 최근 메시지 {@code history} 를 보낸다 (DESIGN §4.14).
 * 브로커를 거치면 구독자 모두에게 가므로, 세션 id · 구독 id 헤더를 단 MESSAGE 를 clientOutboundChannel 에 바로 넣는다.
 */
@Component
public class ChatHistorySender {

	private final ChatBufferService buffer;
	private final SimpMessagingTemplate messagingTemplate;
	private final MessageChannel clientOutboundChannel;

	public ChatHistorySender(ChatBufferService buffer, SimpMessagingTemplate messagingTemplate,
			@Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
		this.buffer = buffer;
		this.messagingTemplate = messagingTemplate;
		this.clientOutboundChannel = clientOutboundChannel;
	}

	@EventListener
	public void onSubscribe(SessionSubscribeEvent event) {
		StompHeaderAccessor subscribe = StompHeaderAccessor.wrap(event.getMessage());
		String destination = subscribe.getDestination();
		Long broadcastId = StompDestinations.broadcastIdOf(destination);
		if (broadcastId == null || subscribe.getSessionId() == null || subscribe.getSubscriptionId() == null) {
			return;
		}
		SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
		headers.setSessionId(subscribe.getSessionId());
		headers.setSubscriptionId(subscribe.getSubscriptionId());
		headers.setDestination(destination);
		headers.setLeaveMutable(true);
		Message<?> message = messagingTemplate.getMessageConverter()
				.toMessage(HistoryMessage.of(buffer.snapshot(broadcastId)), headers.getMessageHeaders());
		if (message != null) {
			clientOutboundChannel.send(message);
		}
	}
}
