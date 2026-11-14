package com.livestream.livestream.chat;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 회원별 채팅 전송 빈도 제한: {@value #LIMIT}건 / {@link #WINDOW}(고정 창). 스팸이 50개 history 버퍼를 밀어내고
 * 메시지마다 DB 조회가 나가는 것을 막는다. 서버 한 대 메모리 기준(§4.14 버퍼와 같은 가정).
 */
@Component
public class ChatRateLimiter {

	static final int LIMIT = 10;
	static final Duration WINDOW = Duration.ofSeconds(10);

	private final Map<Long, long[]> windows = new ConcurrentHashMap<>();
	private final Clock clock;

	@Autowired
	public ChatRateLimiter() {
		this(Clock.systemUTC());
	}

	ChatRateLimiter(Clock clock) {
		this.clock = clock;
	}

	/** 이번 창에서 한도 안이면 세고 true. */
	public boolean tryAcquire(Long memberId) {
		long now = clock.millis();
		boolean[] allowed = {false};
		windows.compute(memberId, (id, w) -> {
			if (w == null || now - w[0] >= WINDOW.toMillis()) {
				w = new long[] {now, 0};
			}
			if (w[1] < LIMIT) {
				w[1]++;
				allowed[0] = true;
			}
			return w;
		});
		return allowed[0];
	}

	/** 지난 창은 지운다(맵이 회원 수만큼 계속 크지 않게). */
	@Scheduled(fixedDelay = 60_000)
	void purgeExpired() {
		long now = clock.millis();
		windows.entrySet().removeIf(e -> now - e.getValue()[0] >= WINDOW.toMillis());
	}
}
