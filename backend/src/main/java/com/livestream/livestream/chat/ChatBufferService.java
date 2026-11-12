package com.livestream.livestream.chat;

import com.livestream.livestream.broadcast.BroadcastEndedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 방송별 최근 메시지(chat + donation) 최대 50개 (DESIGN §4.14). 구독 직후 history 로 보낸다. 메모리만 — 재시작하면 비어 있다.
 * 방송 종료 30분 뒤 지운다(종료 화면에서 잠깐 더 볼 수 있게).
 */
@Service
public class ChatBufferService {

	static final int CAPACITY = 50;
	static final Duration RETAIN_AFTER_END = Duration.ofMinutes(30);

	private final Map<Long, Deque<Object>> buffers = new ConcurrentHashMap<>();
	private final Map<Long, Instant> removeAt = new ConcurrentHashMap<>();

	public void add(Long broadcastId, Object message) {
		Deque<Object> deque = buffers.computeIfAbsent(broadcastId, k -> new ArrayDeque<>());
		synchronized (deque) {
			deque.addLast(message);
			while (deque.size() > CAPACITY) {
				deque.removeFirst();
			}
		}
	}

	/** 오래된 것부터. */
	public List<Object> snapshot(Long broadcastId) {
		Deque<Object> deque = buffers.get(broadcastId);
		if (deque == null) {
			return List.of();
		}
		synchronized (deque) {
			return new ArrayList<>(deque);
		}
	}

	@EventListener
	public void onBroadcastEnded(BroadcastEndedEvent event) {
		removeAt.put(event.broadcastId(), Instant.now().plus(RETAIN_AFTER_END));
	}

	@Scheduled(fixedDelay = 60, timeUnit = TimeUnit.SECONDS)
	public void purgeExpired() {
		purgeExpired(Instant.now());
	}

	void purgeExpired(Instant now) {
		removeAt.forEach((id, at) -> {
			if (!at.isAfter(now)) {
				buffers.remove(id);
				removeAt.remove(id);
			}
		});
	}
}
