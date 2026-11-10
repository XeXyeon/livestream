package com.livestream.livestream.chat;

import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.JwtProvider;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.broadcast.BroadcastRepository;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP 인바운드 검사 (DESIGN §4.14). 여기서 던진 {@link ApiException} 은 {@link StompErrorHandler} 가 ERROR 프레임
 * ({@code message} 헤더 = 코드)으로 바꾸고, 서버는 그 연결을 닫는다.
 * <ul>
 *   <li>CONNECT: {@code Authorization: Bearer} 헤더가 있으면 검증해 Principal 설정. 만료 · 위조면 거절(TOKEN_EXPIRED /
 *       TOKEN_INVALID) — 익명으로 떨어뜨리지 않는다(AUTH-1#3과 같은 이유: 프론트가 refresh 후 재연결해야 한다). 없으면 익명.</li>
 *   <li>SUBSCRIBE: {@code /topic/broadcasts/{id}}(삭제되지 않은 방송) 와 {@code /user/queue/errors} 만, 세션당
 *       {@value #MAX_SUBSCRIPTIONS_PER_SESSION}개까지(구독을 무한히 늘려 메모리 · 시청자 수를 부풀리는 것 방지).</li>
 *   <li>SEND: {@code /app/**} 만(브로커 목적지로 직접 보내 가짜 이벤트를 뿌리는 것 방지), Principal 필수.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

	private static final String BEARER = "Bearer ";
	static final int MAX_SUBSCRIPTIONS_PER_SESSION = 10;

	private final JwtProvider jwtProvider;
	private final BroadcastRepository broadcastRepository;
	/** 세션 → 구독 id. 연결이 끊기면 StompSubProtocolHandler 가 DISCONNECT 를 인바운드로 보내 여기서도 지운다. */
	private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null || accessor.getCommand() == null) {
			return message;
		}
		StompCommand command = accessor.getCommand();
		switch (command) {
			case CONNECT, STOMP -> authenticate(accessor);
			case SUBSCRIBE -> checkSubscribe(accessor);
			case UNSUBSCRIBE -> forget(accessor.getSessionId(), accessor.getSubscriptionId());
			case DISCONNECT -> {
				if (accessor.getSessionId() != null) {
					subscriptions.remove(accessor.getSessionId());
				}
			}
			case SEND -> checkSend(accessor);
			default -> {
			}
		}
		return message;
	}

	private void authenticate(StompHeaderAccessor accessor) {
		String header = accessor.getFirstNativeHeader("Authorization");
		if (header == null || header.isBlank()) {
			return; // 익명 시청
		}
		if (!header.startsWith(BEARER)) {
			throw new ApiException(ErrorCode.TOKEN_INVALID);
		}
		MemberPrincipal member = jwtProvider.parse(header.substring(BEARER.length()).strip());
		accessor.setUser(new StompPrincipal(member));
	}

	private void checkSubscribe(StompHeaderAccessor accessor) {
		String destination = accessor.getDestination();
		Long broadcastId = StompDestinations.broadcastIdOf(destination);
		if (broadcastId == null && !StompDestinations.USER_ERRORS.equals(destination)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "구독할 수 없는 목적지입니다");
		}
		if (broadcastId != null && broadcastRepository.findActive(broadcastId).isEmpty()) {
			throw new ApiException(ErrorCode.BROADCAST_NOT_FOUND);
		}
		String sessionId = accessor.getSessionId();
		String subscriptionId = accessor.getSubscriptionId();
		if (sessionId == null || subscriptionId == null) {
			return; // 브로커가 거절한다
		}
		Set<String> mine = subscriptions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
		synchronized (mine) {
			if (!mine.contains(subscriptionId) && mine.size() >= MAX_SUBSCRIPTIONS_PER_SESSION) {
				throw new ApiException(ErrorCode.FORBIDDEN, "구독이 너무 많습니다");
			}
			mine.add(subscriptionId);
		}
	}

	private void forget(String sessionId, String subscriptionId) {
		if (sessionId == null || subscriptionId == null) {
			return;
		}
		Set<String> mine = subscriptions.get(sessionId);
		if (mine != null) {
			synchronized (mine) {
				mine.remove(subscriptionId);
			}
		}
	}

	/** 테스트용: 세션의 구독 수. */
	int subscriptionCount(String sessionId) {
		Set<String> mine = subscriptions.get(sessionId);
		return mine == null ? 0 : mine.size();
	}

	private static void checkSend(StompHeaderAccessor accessor) {
		String destination = accessor.getDestination();
		if (destination == null || !destination.startsWith("/app/")) {
			throw new ApiException(ErrorCode.FORBIDDEN, "보낼 수 없는 목적지입니다");
		}
		if (StompPrincipal.memberOf(accessor.getUser()) == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
	}
}
