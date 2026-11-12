package com.livestream.livestream.integration;

import com.livestream.livestream.integration.dto.WidgetDonationEvent;
import com.livestream.livestream.integration.dto.WidgetReadyEvent;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 알림 위젯 SSE 연결 관리 (DESIGN §4.15): 채널별 {@link SseEmitter} 목록, 후원 발행, 15초 ping, 위젯 키 재발급 시 모두 닫기.
 * <ul>
 *   <li>보내기는 채널마다 순서대로(가상 스레드) 한다: 느린 연결(OBS 등)이 후원 승인 요청 스레드나 다른 채널의 전송을 붙잡지 않고,
 *       한 연결에 대한 쓰기 순서가 유지된다. 채널마다 대기 작업은 {@link #MAX_PENDING_PER_CHANNEL}개까지(넘으면 버린다 —
 *       후원은 재연결 때 Last-Event-ID 로 다시 온다).</li>
 *   <li>연결은 {@link #EMITTER_TIMEOUT} 뒤 끝난다 — EventSource 는 Last-Event-ID 를 붙여 다시 붙고, 그 사이 후원은 재전송된다.</li>
 *   <li>채널당 {@link #MAX_CONNECTIONS_PER_CHANNEL}개까지. 넘으면 가장 오래된 연결을 닫는다. 연결이 쌓이는 것을 막을 뿐,
 *       키가 새었을 때의 대책은 위젯 키 재발급이다.</li>
 * </ul>
 */
@Slf4j
@Service
public class WidgetEventService {

	static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
	static final int MAX_CONNECTIONS_PER_CHANNEL = 10;
	static final int MAX_PENDING_PER_CHANNEL = 100;

	private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
	/** 지우지 않는다: 지우는 순간 다른 스레드가 옛 것에 넣으면 한 채널에 전송기가 둘이 된다. 위젯 키로 연결한 채널 수만큼만 생긴다. */
	private final Map<Long, SerialSender> senders = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("widget-sse-", 0).factory());

	/** 새 연결: ready 를 보내고, 놓친 후원(replay)을 이어 보낸 뒤 등록한다. */
	public SseEmitter connect(Long channelId, List<WidgetDonationEvent> replay) {
		SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
		emitter.onCompletion(() -> remove(channelId, emitter));
		emitter.onTimeout(() -> {
			remove(channelId, emitter);
			emitter.complete();
		});
		emitter.onError(e -> remove(channelId, emitter));
		try {
			emitter.send(SseEmitter.event().name("ready").data(new WidgetReadyEvent(channelId, WidgetDonationEvent.VERSION),
					MediaType.APPLICATION_JSON));
			for (WidgetDonationEvent event : replay) {
				emitter.send(donationEvent(event));
			}
		} catch (IOException | IllegalStateException e) {
			emitter.completeWithError(e);
			return emitter;
		}
		// 추가와 상한 정리를 한 번에 한다: 따로 하면 그 사이 remove 가 빈 목록을 맵에서 빼 새 연결이 고아가 된다.
		List<SseEmitter> evicted = new ArrayList<>();
		emitters.compute(channelId, (id, list) -> {
			List<SseEmitter> l = list != null ? list : new CopyOnWriteArrayList<>();
			l.add(emitter);
			while (l.size() > MAX_CONNECTIONS_PER_CHANNEL) {
				evicted.add(l.removeFirst());
			}
			return l;
		});
		evicted.forEach(SseEmitter::complete);
		return emitter;
	}

	/** 승인된 후원을 그 채널 위젯들에 보낸다. 커밋 뒤에 부른다. */
	public void publish(Long channelId, WidgetDonationEvent event) {
		if (!emitters.containsKey(channelId)) {
			return;
		}
		submit(channelId, () -> sendAll(channelId, donationEvent(event)));
	}

	/** 15초마다 주석 한 줄: 프록시 · 브라우저가 유휴 연결을 끊지 않게 하고, 끊긴 연결을 찾아 지운다. */
	@Scheduled(fixedDelay = 15_000)
	void ping() {
		emitters.keySet().forEach(channelId -> submit(channelId, () -> sendAll(channelId, SseEmitter.event().comment("ping"))));
	}

	/** 위젯 키 재발급이 커밋되면 그 채널의 연결을 모두 닫는다(옛 키 URL 로 연 위젯이 더는 받지 않게). */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onWidgetKeyRotated(WidgetKeyRotatedEvent event) {
		List<SseEmitter> list = emitters.remove(event.channelId());
		if (list != null) {
			list.forEach(SseEmitter::complete);
		}
	}

	/** 테스트 · 모니터링용: 채널의 연결 수. */
	public int connectionCount(Long channelId) {
		List<SseEmitter> list = emitters.get(channelId);
		return list == null ? 0 : list.size();
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
		emitters.values().forEach(list -> list.forEach(SseEmitter::complete));
		emitters.clear();
	}

	private void submit(Long channelId, Runnable task) {
		if (!senders.computeIfAbsent(channelId, id -> new SerialSender()).submit(task)) {
			log.warn("위젯 전송 대기열이 가득 차 이벤트를 버림: channel={}", channelId);
		}
	}

	/** 실행 시점의 목록에 보낸다(그 사이 붙은 연결은 replay 로 이미 받았거나, 다음 이벤트부터 받는다). */
	private void sendAll(Long channelId, SseEmitter.SseEventBuilder event) {
		List<SseEmitter> list = emitters.get(channelId);
		if (list != null) {
			list.forEach(emitter -> send(channelId, emitter, event));
		}
	}

	private void send(Long channelId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
		try {
			emitter.send(event);
		} catch (IOException | IllegalStateException e) {
			// 끊긴 연결: 목록에서 빼고 닫는다(이미 끝난 emitter 의 complete 는 무시된다).
			remove(channelId, emitter);
			emitter.completeWithError(e);
		}
	}

	private void remove(Long channelId, SseEmitter emitter) {
		emitters.computeIfPresent(channelId, (id, list) -> {
			list.remove(emitter);
			return list.isEmpty() ? null : list;
		});
	}

	private static SseEmitter.SseEventBuilder donationEvent(WidgetDonationEvent event) {
		return SseEmitter.event().name("donation").id(String.valueOf(event.id())).data(event, MediaType.APPLICATION_JSON);
	}

	/** 한 채널의 전송 작업을 하나씩 차례로 돌린다. 도는 동안만 가상 스레드 하나를 쓴다. */
	private final class SerialSender {

		private final Deque<Runnable> tasks = new ArrayDeque<>();
		private boolean running;

		synchronized boolean submit(Runnable task) {
			if (tasks.size() >= MAX_PENDING_PER_CHANNEL) {
				return false;
			}
			tasks.add(task);
			if (!running) {
				try {
					executor.execute(this::drain);
					running = true;
				} catch (RejectedExecutionException e) {
					tasks.clear(); // 종료 중
					return false;
				}
			}
			return true;
		}

		private void drain() {
			while (true) {
				Runnable task;
				synchronized (this) {
					task = tasks.poll();
					if (task == null) {
						running = false;
						return;
					}
				}
				try {
					task.run();
				} catch (RuntimeException e) {
					log.warn("위젯 전송 작업 실패", e);
				}
			}
		}
	}
}
