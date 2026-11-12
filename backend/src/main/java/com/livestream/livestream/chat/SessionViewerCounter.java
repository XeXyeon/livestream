package com.livestream.livestream.chat;

import com.livestream.livestream.chat.dto.ViewersMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * 방송별 동시 시청자 수 = {@code /topic/broadcasts/{id}} 를 구독한 STOMP 세션 수 (DESIGN §4.14).
 * 한 세션이 같은 방송을 두 번 구독해도 1로 센다. {@code viewer-broadcast-interval-seconds} 마다 바뀐 방송에만 viewers 이벤트.
 * 메모리만 — 재시작하면 0부터(클라이언트가 재연결 · 재구독하며 다시 찬다).
 */
@Component
@RequiredArgsConstructor
public class SessionViewerCounter implements ViewerCounter {

	private final SimpMessagingTemplate messagingTemplate;

	/** broadcastId → (sessionId → 그 세션의 구독 수) */
	private final Map<Long, Map<String, Integer>> sessionsByBroadcast = new HashMap<>();
	/** sessionId → (subscriptionId → broadcastId) */
	private final Map<String, Map<String, Long>> subscriptionsBySession = new HashMap<>();
	/** 마지막으로 보낸 값 */
	private final Map<Long, Long> lastSent = new HashMap<>();

	@Override
	public synchronized long count(Long broadcastId) {
		Map<String, Integer> sessions = sessionsByBroadcast.get(broadcastId);
		return sessions == null ? 0 : sessions.size();
	}

	@EventListener
	public void onSubscribe(SessionSubscribeEvent event) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
		Long broadcastId = StompDestinations.broadcastIdOf(accessor.getDestination());
		if (broadcastId != null && accessor.getSessionId() != null && accessor.getSubscriptionId() != null) {
			subscribed(accessor.getSessionId(), accessor.getSubscriptionId(), broadcastId);
		}
	}

	@EventListener
	public void onUnsubscribe(SessionUnsubscribeEvent event) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
		if (accessor.getSessionId() != null && accessor.getSubscriptionId() != null) {
			unsubscribed(accessor.getSessionId(), accessor.getSubscriptionId());
		}
	}

	@EventListener
	public void onDisconnect(SessionDisconnectEvent event) {
		disconnected(event.getSessionId());
	}

	synchronized void subscribed(String sessionId, String subscriptionId, Long broadcastId) {
		Long previous = subscriptionsBySession.computeIfAbsent(sessionId, k -> new HashMap<>()).put(subscriptionId, broadcastId);
		if (previous != null) {
			release(sessionId, previous); // 같은 구독 id 재사용
		}
		sessionsByBroadcast.computeIfAbsent(broadcastId, k -> new HashMap<>()).merge(sessionId, 1, Integer::sum);
	}

	synchronized void unsubscribed(String sessionId, String subscriptionId) {
		Map<String, Long> subs = subscriptionsBySession.get(sessionId);
		if (subs == null) {
			return;
		}
		Long broadcastId = subs.remove(subscriptionId);
		if (subs.isEmpty()) {
			subscriptionsBySession.remove(sessionId);
		}
		if (broadcastId != null) {
			release(sessionId, broadcastId);
		}
	}

	synchronized void disconnected(String sessionId) {
		Map<String, Long> subs = subscriptionsBySession.remove(sessionId);
		if (subs != null) {
			subs.values().forEach(broadcastId -> release(sessionId, broadcastId));
		}
	}

	private void release(String sessionId, Long broadcastId) {
		Map<String, Integer> sessions = sessionsByBroadcast.get(broadcastId);
		if (sessions == null) {
			return;
		}
		sessions.computeIfPresent(sessionId, (k, n) -> n <= 1 ? null : n - 1);
		if (sessions.isEmpty()) {
			sessionsByBroadcast.remove(broadcastId);
		}
	}

	@Scheduled(fixedDelayString = "${app.broadcast.viewer-broadcast-interval-seconds}", timeUnit = TimeUnit.SECONDS)
	public void publishChanges() {
		Map<Long, Long> changed = new HashMap<>();
		synchronized (this) {
			sessionsByBroadcast.forEach((id, sessions) -> {
				long count = sessions.size();
				if (!Long.valueOf(count).equals(lastSent.get(id))) {
					changed.put(id, count);
				}
			});
			// 0이 된 방송은 한 번 0을 보내고 잊는다
			lastSent.keySet().stream().filter(id -> !sessionsByBroadcast.containsKey(id)).toList()
					.forEach(id -> changed.put(id, 0L));
			changed.forEach((id, count) -> {
				if (count == 0) {
					lastSent.remove(id);
				} else {
					lastSent.put(id, count);
				}
			});
		}
		changed.forEach((id, count) ->
				messagingTemplate.convertAndSend(StompDestinations.broadcastTopic(id), ViewersMessage.of(count)));
	}
}
