package com.livestream.livestream.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * CHAT-1 (DESIGN §4.14 · §9 CHAT-1): 실제 STOMP 클라이언트로 익명 구독 · history, 로그인 전송 · 브로드캐스트,
 * 익명 전송 거부, CONNECT 토큰 거절, 전송 실패 → /user/queue/errors, 브로커 목적지 직접 SEND 거부, 구독 수 · viewers, status 이벤트.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatStompTest {

	@LocalServerPort int port;
	@Autowired MockMvc mvc;
	@Autowired AppProperties props;
	@Autowired BroadcastLifecycleService lifecycle;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired SessionViewerCounter viewerCounter;
	@MockitoBean MediaMtxClient mediaMtxClient;

	private final WebSocketStompClient client = newClient();
	private final List<StompSession> sessions = new java.util.ArrayList<>();

	@AfterEach
	void tearDown() {
		sessions.forEach(s -> {
			if (s.isConnected()) {
				s.disconnect();
			}
		});
	}

	@Test
	void 익명은_구독해_history를_받고_로그인_회원의_채팅을_실시간으로_받는다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long broadcastId = startBroadcast(streamer);

		StompSession anon = connect(null);
		BlockingQueue<Map<String, Object>> anonTopic = subscribe(anon, topic(broadcastId));
		Map<String, Object> history = poll(anonTopic);
		assertThat(history.get("type")).isEqualTo("history");
		assertThat((List<?>) history.get("messages")).isEmpty();

		StompSession v = connect(viewer.access());
		subscribe(v, topic(broadcastId));
		settle();
		v.send("/app/broadcasts/" + broadcastId + "/chat", Map.of("content", "  안녕하세요  "));

		Map<String, Object> chat = pollType(anonTopic, "chat");
		assertThat(chat.get("content")).isEqualTo("안녕하세요");
		assertThat(chat.get("id")).asString().hasSize(36);
		assertThat(chat.get("sentAt")).asString().endsWith("Z");
		@SuppressWarnings("unchecked") Map<String, Object> member = (Map<String, Object>) chat.get("member");
		assertThat(((Number) member.get("id")).longValue()).isEqualTo(viewer.memberId());
		assertThat(member.get("nickname")).isEqualTo(viewer.nickname());
		assertThat(member.get("isChannelOwner")).isEqualTo(false);

		// 방송인 배지
		StompSession s = connect(streamer.access());
		BlockingQueue<Map<String, Object>> streamerTopic = subscribe(s, topic(broadcastId));
		Map<String, Object> streamerHistory = poll(streamerTopic);
		assertThat((List<?>) streamerHistory.get("messages")).hasSize(1); // 입장 시 최근 메시지
		settle();
		s.send("/app/broadcasts/" + broadcastId + "/chat", Map.of("content", "환영합니다"));
		@SuppressWarnings("unchecked") Map<String, Object> ownerMember = (Map<String, Object>) pollType(anonTopic, "chat").get("member");
		assertThat(ownerMember.get("isChannelOwner")).isEqualTo(true);
	}

	@Test
	void 익명_전송은_ERROR_프레임으로_거절한다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		long broadcastId = startBroadcast(streamer);
		ErrorCapture errors = new ErrorCapture();
		StompSession anon = connect(null, errors);

		anon.send("/app/broadcasts/" + broadcastId + "/chat", Map.of("content", "익명"));

		assertThat(errors.messages.poll(5, TimeUnit.SECONDS)).isEqualTo("UNAUTHENTICATED");
		await(() -> !anon.isConnected()); // ERROR 프레임 뒤 서버가 연결을 닫는다
	}

	@Test
	void 없는_방송_앞자리_0_세션당_한도를_넘는_구독은_거절한다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		long broadcastId = startBroadcast(streamer);

		ErrorCapture missing = new ErrorCapture();
		connect(null, missing).subscribe(topic(Long.MAX_VALUE / 10), frameHandler(new LinkedBlockingQueue<>()));
		assertThat(missing.messages.poll(5, TimeUnit.SECONDS)).isEqualTo("BROADCAST_NOT_FOUND");

		ErrorCapture zero = new ErrorCapture();
		connect(null, zero).subscribe("/topic/broadcasts/0" + broadcastId, frameHandler(new LinkedBlockingQueue<>()));
		assertThat(zero.messages.poll(5, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");

		ErrorCapture many = new ErrorCapture();
		StompSession s = connect(null, many);
		for (int i = 0; i < StompAuthInterceptor.MAX_SUBSCRIPTIONS_PER_SESSION; i++) {
			s.subscribe(topic(broadcastId), frameHandler(new LinkedBlockingQueue<>()));
		}
		settle();
		assertThat(many.messages).isEmpty();
		s.subscribe(topic(broadcastId), frameHandler(new LinkedBlockingQueue<>()));
		assertThat(many.messages.poll(5, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");
	}

	@Test
	void 만료_위조_토큰의_CONNECT는_거절한다() throws Exception {
		ErrorCapture expired = new ErrorCapture();
		connectQuietly(expiredToken(), expired);
		assertThat(expired.messages.poll(5, TimeUnit.SECONDS)).isEqualTo("TOKEN_EXPIRED");

		ErrorCapture forged = new ErrorCapture();
		connectQuietly("not-a-jwt", forged);
		assertThat(forged.messages.poll(5, TimeUnit.SECONDS)).isEqualTo("TOKEN_INVALID");
	}

	@Test
	void 브로커_목적지로_직접_보내거나_다른_목적지를_구독하면_거절한다() throws Exception {
		Account viewer = TestAccounts.create(mvc);
		ErrorCapture errors = new ErrorCapture();
		StompSession v = connect(viewer.access(), errors);
		v.send("/topic/broadcasts/1", Map.of("type", "donation", "amount", 100000));
		assertThat(errors.messages.poll(5, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");

		ErrorCapture errors2 = new ErrorCapture();
		StompSession v2 = connect(viewer.access(), errors2);
		v2.subscribe("/topic/anything", frameHandler(new LinkedBlockingQueue<>()));
		assertThat(errors2.messages.poll(5, TimeUnit.SECONDS)).isEqualTo("FORBIDDEN");
	}

	@Test
	void 전송_실패는_연결을_끊지_않고_내_에러_큐로_온다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account viewer = TestAccounts.create(mvc);
		long broadcastId = startBroadcast(streamer);
		StompSession v = connect(viewer.access());
		BlockingQueue<Map<String, Object>> errors = subscribe(v, "/user/queue/errors");
		BlockingQueue<Map<String, Object>> t = subscribe(v, topic(broadcastId));
		poll(t);
		settle();

		v.send("/app/broadcasts/" + broadcastId + "/chat", Map.of("content", "가".repeat(201)));
		assertThat(poll(errors).get("code")).isEqualTo("VALIDATION_ERROR");
		v.send("/app/broadcasts/" + broadcastId + "/chat", Map.of("content", "   "));
		assertThat(poll(errors).get("code")).isEqualTo("VALIDATION_ERROR");

		lifecycle.endOngoingOf(streamer.channelId());
		assertThat(pollType(t, "status").get("status")).isEqualTo("ENDED");
		v.send("/app/broadcasts/" + broadcastId + "/chat", Map.of("content", "늦은 채팅"));
		Map<String, Object> err = poll(errors);
		assertThat(err.get("code")).isEqualTo("BROADCAST_NOT_LIVE");
		assertThat(err.get("message")).isEqualTo("방송 중이 아닙니다");
		assertThat(v.isConnected()).isTrue();
	}

	@Test
	void 구독_세션_수를_세고_바뀌면_viewers를_보낸다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		long broadcastId = startBroadcast(streamer);
		StompSession a = connect(null);
		BlockingQueue<Map<String, Object>> qa = subscribe(a, topic(broadcastId));
		StompSession b = connect(null);
		subscribe(b, topic(broadcastId));
		subscribe(b, topic(broadcastId)); // 같은 세션 두 번 → 1명
		await(() -> viewerCounter.count(broadcastId) == 2);

		viewerCounter.publishChanges();
		assertThat(((Number) pollType(qa, "viewers").get("count")).longValue()).isEqualTo(2);

		b.disconnect();
		await(() -> viewerCounter.count(broadcastId) == 1);
		viewerCounter.publishChanges();
		assertThat(((Number) pollType(qa, "viewers").get("count")).longValue()).isEqualTo(1);

		// 상세 API 도 같은 값을 쓴다
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/broadcasts/" + broadcastId))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.viewerCount").value(1));
	}

	// ---------- 도우미 ----------

	private long startBroadcast(Account streamer) {
		lifecycle.onReady(streamer.channelId(), BroadcastSource.BROWSER_PC);
		return broadcastRepository.findOngoing(streamer.channelId()).orElseThrow().getId();
	}

	private static String topic(long broadcastId) {
		return "/topic/broadcasts/" + broadcastId;
	}

	private static WebSocketStompClient newClient() {
		WebSocketStompClient c = new WebSocketStompClient(new StandardWebSocketClient());
		c.setMessageConverter(new JacksonJsonMessageConverter());
		return c;
	}

	private StompSession connect(String access) throws Exception {
		return connect(access, new StompSessionHandlerAdapter() {
		});
	}

	private StompSession connect(String access, StompSessionHandlerAdapter handler) throws Exception {
		StompHeaders headers = new StompHeaders();
		if (access != null) {
			headers.add("Authorization", "Bearer " + access);
		}
		StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, handler)
				.get(5, TimeUnit.SECONDS);
		sessions.add(session);
		return session;
	}

	private void connectQuietly(String access, StompSessionHandlerAdapter handler) {
		StompHeaders headers = new StompHeaders();
		headers.add("Authorization", "Bearer " + access);
		client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, handler);
	}

	private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
		BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
		session.subscribe(destination, frameHandler(queue));
		return queue;
	}

	private static StompFrameHandler frameHandler(BlockingQueue<Map<String, Object>> queue) {
		return new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return Map.class;
			}

			@Override
			@SuppressWarnings("unchecked")
			public void handleFrame(StompHeaders headers, Object payload) {
				queue.add((Map<String, Object>) payload);
			}
		};
	}

	private static Map<String, Object> poll(BlockingQueue<Map<String, Object>> queue) throws InterruptedException {
		Map<String, Object> m = queue.poll(5, TimeUnit.SECONDS);
		assertThat(m).as("5초 안에 메시지가 와야 한다").isNotNull();
		return m;
	}

	/** 그 type 이 올 때까지 다른 type(viewers 등)은 건너뛴다. */
	private static Map<String, Object> pollType(BlockingQueue<Map<String, Object>> queue, String type) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline) {
			Map<String, Object> m = queue.poll(200, TimeUnit.MILLISECONDS);
			if (m != null && type.equals(m.get("type"))) {
				return m;
			}
		}
		throw new AssertionError(type + " 메시지가 오지 않음");
	}

	/** 브로커가 구독을 등록할 시간(인바운드 채널은 비동기). */
	private static void settle() throws InterruptedException {
		Thread.sleep(300);
	}

	private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(condition.getAsBoolean()).isTrue();
	}

	private String expiredToken() {
		Instant past = Instant.now().minusSeconds(3600);
		return Jwts.builder().subject("1").claim("nickname", "x").claim("channelId", 1)
				.issuedAt(Date.from(past.minusSeconds(60))).expiration(Date.from(past))
				.signWith(Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8))).compact();
	}

	/** ERROR 프레임의 message 헤더를 모은다. */
	static class ErrorCapture extends StompSessionHandlerAdapter {

		final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

		@Override
		public Type getPayloadType(StompHeaders headers) {
			return Map.class;
		}

		@Override
		public void handleFrame(StompHeaders headers, Object payload) {
			String message = headers.getFirst("message");
			if (message != null) {
				messages.add(message);
			}
		}
	}
}
