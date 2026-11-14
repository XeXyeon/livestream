package com.livestream.livestream.broadcast;

import com.livestream.livestream.common.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 종료 뒤 publish 차단 (DESIGN §4.6 "end 공통"). 소유자 종료 · 유예 만료 뒤 {@code end-block-seconds} 동안 그 채널의
 * publish 를 인증 콜백이 401로 거절해, kick 당한 OBS · WhipClient 의 자동 재연결이 새 방송을 만들지 못하게 한다.
 * 메모리 보관이라 서버 재시작 시 사라진다(허용).
 */
@Component
public class PublishBlockRegistry {

	private final Map<Long, Instant> blockedUntil = new ConcurrentHashMap<>();
	private final Duration duration;
	private final Clock clock;

	@Autowired
	public PublishBlockRegistry(AppProperties props) {
		this(Duration.ofSeconds(props.broadcast().endBlockSeconds()), Clock.systemUTC());
	}

	PublishBlockRegistry(Duration duration, Clock clock) {
		this.duration = duration;
		this.clock = clock;
	}

	public void block(Long channelId) {
		blockedUntil.put(channelId, clock.instant().plus(duration));
	}

	public boolean isBlocked(Long channelId) {
		Instant until = blockedUntil.get(channelId);
		if (until == null) {
			return false;
		}
		if (clock.instant().isBefore(until)) {
			return true;
		}
		blockedUntil.remove(channelId, until);
		return false;
	}
}
