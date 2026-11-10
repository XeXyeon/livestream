package com.livestream.livestream.common.config;

import com.livestream.livestream.chat.StompAuthInterceptor;
import com.livestream.livestream.chat.StompErrorHandler;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket (DESIGN §4.14). 엔드포인트 {@code /ws}(네이티브, 같은 출처), 브로커 SimpleBroker.
 * <ul>
 *   <li>{@code /topic/broadcasts/{id}}: 방송별 이벤트(chat · donation · status · viewers · history)</li>
 *   <li>{@code /user/queue/errors}: 로그인 회원의 채팅 전송 실패 {@code {code, message}} (그 세션에만)</li>
 *   <li>앱 접두어 {@code /app} — 전송 {@code /app/broadcasts/{id}/chat}</li>
 * </ul>
 * 하트비트 10초: 끊긴 연결(모바일 · 프록시)을 정리해 시청자 수가 남지 않게 한다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private static final long HEARTBEAT_MILLIS = 10_000;
	/** 채팅은 200자라 이보다 큰 인바운드 프레임은 받을 이유가 없다(기본 64KB). */
	private static final int INBOUND_MESSAGE_LIMIT = 8 * 1024;

	private final StompAuthInterceptor stompAuthInterceptor;
	private ThreadPoolTaskScheduler heartbeat;

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws");
		registry.setErrorHandler(new StompErrorHandler());
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		// 빈으로 등록하지 않는다: TaskScheduler 빈이 늘면 @Scheduled 가 쓸 스케줄러를 고르지 못한다.
		heartbeat = new ThreadPoolTaskScheduler();
		heartbeat.setPoolSize(1);
		heartbeat.setThreadNamePrefix("stomp-heartbeat-");
		heartbeat.setDaemon(true);
		heartbeat.initialize();
		registry.enableSimpleBroker("/topic", "/queue")
				.setHeartbeatValue(new long[] {HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
				.setTaskScheduler(heartbeat);
		registry.setApplicationDestinationPrefixes("/app");
		registry.setUserDestinationPrefix("/user");
	}

	@Override
	public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
		registration.setMessageSizeLimit(INBOUND_MESSAGE_LIMIT);
	}

	@PreDestroy
	void shutdownHeartbeat() {
		if (heartbeat != null) {
			heartbeat.shutdown();
		}
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(stompAuthInterceptor);
	}
}
