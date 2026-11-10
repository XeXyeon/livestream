package com.livestream.livestream.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.livestream.livestream.common.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ChatContentTest {

	@Test
	void 앞뒤_공백을_지우고_이모지_조합은_받는다() {
		assertThat(ChatService.content("  안녕 👨‍👩‍👧 ")).isEqualTo("안녕 👨‍👩‍👧");
		assertThat(ChatService.content("가".repeat(200))).hasSize(200);
	}

	@Test
	void 보이지_않는_문자만_있거나_제어_양방향_문자면_거절한다() {
		for (String bad : new String[] {"", "   ", "​", "​‍", "a\u0007b", "a\u0085b", "a‮b", "a b",
				"a⁦b", "가".repeat(201)}) {
			assertThatThrownBy(() -> ChatService.content(bad)).as(bad).isInstanceOf(ApiException.class);
		}
	}

	@Test
	void 회원별로_창마다_한도까지만_보낸다() {
		AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-10T00:00:00Z"));
		Clock clock = new Clock() {
			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now.get();
			}
		};
		ChatRateLimiter limiter = new ChatRateLimiter(clock);
		for (int i = 0; i < ChatRateLimiter.LIMIT; i++) {
			assertThat(limiter.tryAcquire(1L)).isTrue();
		}
		assertThat(limiter.tryAcquire(1L)).isFalse();
		assertThat(limiter.tryAcquire(2L)).isTrue();
		now.set(now.get().plus(ChatRateLimiter.WINDOW));
		assertThat(limiter.tryAcquire(1L)).isTrue();
	}
}
