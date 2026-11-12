package com.livestream.livestream.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.livestream.livestream.broadcast.BroadcastEndedEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChatBufferServiceTest {

	@Test
	void 오십개를_넘으면_오래된_것부터_버린다() {
		ChatBufferService buffer = new ChatBufferService();
		for (int i = 1; i <= 55; i++) {
			buffer.add(1L, "m" + i);
		}
		assertThat(buffer.snapshot(1L)).hasSize(50).first().isEqualTo("m6");
		assertThat(buffer.snapshot(1L)).last().isEqualTo("m55");
		assertThat(buffer.snapshot(2L)).isEmpty();
	}

	@Test
	void 종료_30분_뒤에_지운다() {
		ChatBufferService buffer = new ChatBufferService();
		buffer.add(1L, "a");
		buffer.onBroadcastEnded(new BroadcastEndedEvent(1L, 10L, null));
		buffer.purgeExpired(Instant.now().plus(ChatBufferService.RETAIN_AFTER_END).minusSeconds(60));
		assertThat(buffer.snapshot(1L)).containsExactly("a");
		buffer.purgeExpired(Instant.now().plus(ChatBufferService.RETAIN_AFTER_END).plusSeconds(60));
		assertThat(buffer.snapshot(1L)).isEmpty();
	}
}
