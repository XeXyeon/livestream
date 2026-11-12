package com.livestream.livestream.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PublishBlockRegistryTest {

	static class MutableClock extends Clock {
		Instant now = Instant.parse("2026-11-10T00:00:00Z");

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public java.time.ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}
	}

	@Test
	void 차단은_정해진_시간_동안만_유지되고_채널별이다() {
		MutableClock clock = new MutableClock();
		PublishBlockRegistry registry = new PublishBlockRegistry(Duration.ofSeconds(30), clock);

		registry.block(1L);
		assertThat(registry.isBlocked(1L)).isTrue();
		assertThat(registry.isBlocked(2L)).isFalse();

		clock.now = clock.now.plusSeconds(29);
		assertThat(registry.isBlocked(1L)).isTrue();
		clock.now = clock.now.plusSeconds(1);
		assertThat(registry.isBlocked(1L)).isFalse();

		registry.block(1L); // 다시 걸면 새로 30초
		assertThat(registry.isBlocked(1L)).isTrue();
	}
}
