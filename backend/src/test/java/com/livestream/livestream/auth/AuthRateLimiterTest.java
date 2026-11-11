package com.livestream.livestream.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.error.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** AUTH-1#1 단위 테스트: Clock 을 주입해 고정 창 동작을 검증한다 (DESIGN §4.7). */
class AuthRateLimiterTest {

	private final MutableClock clock = new MutableClock();
	private final AuthRateLimiter limiter = new AuthRateLimiter(new AppProperties.Auth(3, 3, 300), clock);

	@Test
	void 로그인_실패가_한도에_이르면_다음_시도부터_429() {
		for (int i = 0; i < 3; i++) {
			limiter.checkLogin("1.1.1.1", "a@x.com");
			limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		}
		assertThatThrownBy(() -> limiter.checkLogin("1.1.1.1", "a@x.com")).isInstanceOf(TooManyRequestsException.class);
	}

	@Test
	void Retry_After는_창이_끝날_때까지_남은_초() {
		limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		clock.advance(Duration.ofSeconds(100));
		limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		clock.advance(Duration.ofMillis(500));
		assertThatThrownBy(() -> limiter.checkLogin("1.1.1.1", "a@x.com"))
				.isInstanceOfSatisfying(TooManyRequestsException.class, e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(200)); // 199.5 → 올림
	}

	@Test
	void 성공하면_그_키의_카운터가_초기화된다() {
		limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		limiter.clearLogin("1.1.1.1", "a@x.com");
		limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		assertThatCode(() -> limiter.checkLogin("1.1.1.1", "a@x.com")).doesNotThrowAnyException();
	}

	@Test
	void 창이_지나면_다시_허용된다() {
		for (int i = 0; i < 3; i++) {
			limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		}
		clock.advance(Duration.ofSeconds(299));
		assertThatThrownBy(() -> limiter.checkLogin("1.1.1.1", "a@x.com")).isInstanceOf(TooManyRequestsException.class);
		clock.advance(Duration.ofSeconds(1));
		assertThatCode(() -> limiter.checkLogin("1.1.1.1", "a@x.com")).doesNotThrowAnyException();
		limiter.recordLoginFailure("1.1.1.1", "a@x.com"); // 새 창이 1부터 센다
		assertThatCode(() -> limiter.checkLogin("1.1.1.1", "a@x.com")).doesNotThrowAnyException();
	}

	@Test
	void 다른_IP_다른_이메일은_영향받지_않는다() {
		for (int i = 0; i < 3; i++) {
			limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		}
		assertThatCode(() -> limiter.checkLogin("2.2.2.2", "a@x.com")).doesNotThrowAnyException();
		assertThatCode(() -> limiter.checkLogin("1.1.1.1", "b@x.com")).doesNotThrowAnyException();
	}

	@Test
	void 가입은_IP당_한도를_넘는_요청부터_429() {
		for (int i = 0; i < 3; i++) {
			limiter.acquireSignup("1.1.1.1");
		}
		assertThatThrownBy(() -> limiter.acquireSignup("1.1.1.1")).isInstanceOf(TooManyRequestsException.class);
		assertThatCode(() -> limiter.acquireSignup("2.2.2.2")).doesNotThrowAnyException();
		clock.advance(Duration.ofSeconds(300));
		assertThatCode(() -> limiter.acquireSignup("1.1.1.1")).doesNotThrowAnyException();
	}

	@Test
	void 지난_창은_purge가_지운다() {
		limiter.recordLoginFailure("1.1.1.1", "a@x.com");
		limiter.acquireSignup("1.1.1.1");
		clock.advance(Duration.ofSeconds(300));
		limiter.purgeExpired();
		assertThat(limiter.sizeForTest()).isZero();
	}

	/** 테스트에서 시간을 직접 움직인다. */
	private static final class MutableClock extends Clock {
		private Instant now = Instant.parse("2026-11-11T00:00:00Z");

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
