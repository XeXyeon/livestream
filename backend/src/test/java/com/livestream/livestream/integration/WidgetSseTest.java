package com.livestream.livestream.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.donation.TossPaymentsClient;
import com.livestream.livestream.donation.TossPaymentsClient.Result;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WID-1 (DESIGN §4.15 · §9 WID-1): 실제 HTTP 로 SSE 를 받는다. ready → 승인 후원 donation(id = 후원 id),
 * Last-Event-ID 헤더 · lastEventId 쿼리 뒤 재전송(헤더 우선), 위젯 키 재발급 시 연결 종료 · 옛 키 404, 없는 키 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WidgetSseTest {

	/** 스트림이 끝났다는 표시(큐에 넣는다). */
	private static final Map<String, String> END = Map.of("event", "__end__");

	@LocalServerPort int port;
	@Autowired MockMvc mvc;
	@Autowired BroadcastLifecycleService lifecycle;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired WidgetEventService widgetEventService;
	@MockitoBean TossPaymentsClient toss;
	@MockitoBean MediaMtxClient mediaMtxClient;

	private final HttpClient http = HttpClient.newHttpClient();
	private final List<Stream<String>> streams = new ArrayList<>();

	@AfterEach
	void tearDown() {
		streams.forEach(Stream::close);
	}

	@Test
	void 연결하면_ready_뒤_승인된_후원을_donation_이벤트로_받는다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);
		String key = widgetKey(streamer);

		BlockingQueue<Map<String, String>> events = open(key, null);
		Map<String, String> ready = next(events);
		assertThat(ready.get("event")).isEqualTo("ready");
		assertThat(((Number) JsonPath.read(ready.get("data"), "$.channelId")).longValue()).isEqualTo(streamer.channelId());
		assertThat((Integer) JsonPath.read(ready.get("data"), "$.version")).isEqualTo(1);
		awaitConnections(streamer.channelId(), 1);

		long donationId = donate(donor, broadcastId, 5000, "응원해요");

		Map<String, String> donation = next(events);
		assertThat(donation.get("event")).isEqualTo("donation");
		assertThat(donation.get("id")).isEqualTo(String.valueOf(donationId));
		String data = donation.get("data");
		assertThat((Integer) JsonPath.read(data, "$.version")).isEqualTo(1);
		assertThat((String) JsonPath.read(data, "$.type")).isEqualTo("donation");
		assertThat(((Number) JsonPath.read(data, "$.id")).longValue()).isEqualTo(donationId);
		assertThat(((Number) JsonPath.read(data, "$.broadcastId")).longValue()).isEqualTo(broadcastId);
		assertThat((String) JsonPath.read(data, "$.donor.nickname")).isEqualTo(donor.nickname());
		assertThat((Integer) JsonPath.read(data, "$.amount")).isEqualTo(5000);
		assertThat((String) JsonPath.read(data, "$.message")).isEqualTo("응원해요");
		assertThat((String) JsonPath.read(data, "$.createdAt")).endsWith("Z");
	}

	@Test
	void Last_Event_ID가_오면_그_뒤_승인_후원을_먼저_보낸다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);
		String key = widgetKey(streamer);
		long first = donate(donor, broadcastId, 1000, "하나");
		long second = donate(donor, broadcastId, 2000, "둘");
		long third = donate(donor, broadcastId, 3000, null);

		BlockingQueue<Map<String, String>> events = open(key, String.valueOf(first));
		assertThat(next(events).get("event")).isEqualTo("ready");
		assertThat(next(events).get("id")).isEqualTo(String.valueOf(second));
		assertThat(next(events).get("id")).isEqualTo(String.valueOf(third));

		// 다른 채널의 후원 id · 형식 오류는 재전송하지 않는다
		Account other = TestAccounts.create(mvc);
		BlockingQueue<Map<String, String>> otherEvents = open(widgetKey(other), String.valueOf(first));
		assertThat(next(otherEvents).get("event")).isEqualTo("ready");
		BlockingQueue<Map<String, String>> garbage = open(key, "abc");
		assertThat(next(garbage).get("event")).isEqualTo("ready");
		assertThat(otherEvents.poll(500, TimeUnit.MILLISECONDS)).isNull();
		assertThat(garbage.poll(500, TimeUnit.MILLISECONDS)).isNull();
	}

	@Test
	void lastEventId_쿼리로도_그_뒤_승인_후원을_먼저_보낸다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);
		long first = donate(donor, broadcastId, 1000, "하나");
		long second = donate(donor, broadcastId, 2000, "둘");

		BlockingQueue<Map<String, String>> events = open(widgetKey(streamer), "?lastEventId=" + first, null);
		assertThat(next(events).get("event")).isEqualTo("ready");
		assertThat(next(events).get("id")).isEqualTo(String.valueOf(second));
		assertThat(events.poll(500, TimeUnit.MILLISECONDS)).isNull();
	}

	@Test
	void 헤더와_쿼리가_함께_오면_헤더가_우선한다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);
		long first = donate(donor, broadcastId, 1000, "하나");
		long second = donate(donor, broadcastId, 2000, "둘");
		long third = donate(donor, broadcastId, 3000, "셋");

		BlockingQueue<Map<String, String>> events = open(widgetKey(streamer), "?lastEventId=" + first, String.valueOf(second));
		assertThat(next(events).get("event")).isEqualTo("ready");
		assertThat(next(events).get("id")).isEqualTo(String.valueOf(third));
		assertThat(events.poll(500, TimeUnit.MILLISECONDS)).isNull();
	}

	@Test
	void 재전송은_승인_시각_순서다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);
		long base = donate(donor, broadcastId, 1000, "기준");
		Order earlier = order(donor, broadcastId, 2000, "먼저 주문");
		Order later = order(donor, broadcastId, 3000, "나중 주문");
		confirm(donor, later);
		confirm(donor, earlier);

		BlockingQueue<Map<String, String>> events = open(widgetKey(streamer), String.valueOf(base));
		assertThat(next(events).get("event")).isEqualTo("ready");
		assertThat(next(events).get("id")).isEqualTo(String.valueOf(later.donationId()));
		assertThat(next(events).get("id")).isEqualTo(String.valueOf(earlier.donationId()));
	}

	@Test
	void 방송이_끝난_뒤_승인된_후원도_위젯에는_간다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);
		Order order = order(donor, broadcastId, 4000, "끝나고 승인");
		mvc.perform(post("/api/broadcasts/" + broadcastId + "/end").header(HttpHeaders.AUTHORIZATION, streamer.bearer()))
				.andExpect(status().is2xxSuccessful());

		BlockingQueue<Map<String, String>> events = open(widgetKey(streamer), null);
		assertThat(next(events).get("event")).isEqualTo("ready");
		awaitConnections(streamer.channelId(), 1);
		confirm(donor, order);
		assertThat(next(events).get("id")).isEqualTo(String.valueOf(order.donationId()));
	}

	@Test
	void 채널당_상한을_넘으면_가장_오래된_연결을_닫는다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		String key = widgetKey(streamer);
		List<BlockingQueue<Map<String, String>>> conns = new ArrayList<>();
		for (int i = 0; i <= WidgetEventService.MAX_CONNECTIONS_PER_CHANNEL; i++) {
			BlockingQueue<Map<String, String>> q = open(key, null);
			assertThat(next(q).get("event")).isEqualTo("ready");
			awaitConnections(streamer.channelId(), Math.min(i + 1, WidgetEventService.MAX_CONNECTIONS_PER_CHANNEL));
			conns.add(q);
		}
		assertThat(next(conns.getFirst())).isEqualTo(END);
		assertThat(conns.get(1).poll(300, TimeUnit.MILLISECONDS)).isNull();
	}

	@Test
	void 위젯_키를_재발급하면_연결이_끊기고_옛_키는_404() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		String key = widgetKey(streamer);
		BlockingQueue<Map<String, String>> events = open(key, null);
		assertThat(next(events).get("event")).isEqualTo("ready");
		awaitConnections(streamer.channelId(), 1);

		String rotated = mvc.perform(post("/api/channels/me/widget-key/rotate").header(HttpHeaders.AUTHORIZATION, streamer.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String newKey = JsonPath.read(rotated, "$.widgetKey");
		assertThat(newKey).isNotEqualTo(key);

		assertThat(next(events)).isEqualTo(END);
		assertThat(widgetEventService.connectionCount(streamer.channelId())).isZero();
		assertThat(httpStatus(key)).isEqualTo(404);
		assertThat(next(open(newKey, null)).get("event")).isEqualTo("ready");
	}

	@Test
	void 없는_키_이상한_키는_404() throws Exception {
		assertThat(httpStatus("0".repeat(32))).isEqualTo(404);
		assertThat(httpStatus("a%20b")).isEqualTo(404);
	}

	// ---------- 도우미 ----------

	private long start(Account streamer) {
		lifecycle.onReady(streamer.channelId(), BroadcastSource.BROWSER_PC);
		return broadcastRepository.findOngoing(streamer.channelId()).orElseThrow().getId();
	}

	private String widgetKey(Account a) throws Exception {
		return JsonPath.read(mvc.perform(get("/api/channels/me").header(HttpHeaders.AUTHORIZATION, a.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.widgetKey");
	}

	/** 주문 → confirm(Toss mock 승인). 반환은 후원 id. */
	private long donate(Account donor, long broadcastId, int amount, String message) throws Exception {
		Order order = order(donor, broadcastId, amount, message);
		confirm(donor, order);
		return order.donationId();
	}

	private record Order(long donationId, String orderId, int amount) {
	}

	private Order order(Account donor, long broadcastId, int amount, String message) throws Exception {
		String body = message == null ? "{\"amount\":" + amount + "}"
				: "{\"amount\":" + amount + ",\"message\":\"" + message + "\"}";
		String created = mvc.perform(post("/api/broadcasts/" + broadcastId + "/donations")
						.header(HttpHeaders.AUTHORIZATION, donor.bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return new Order(((Number) JsonPath.read(created, "$.donationId")).longValue(), JsonPath.read(created, "$.orderId"), amount);
	}

	private void confirm(Account donor, Order order) throws Exception {
		when(toss.confirm(anyString(), anyString(), anyLong())).thenReturn(Result.approved());
		mvc.perform(post("/api/donations/confirm").header(HttpHeaders.AUTHORIZATION, donor.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"paymentKey\":\"pk-" + order.orderId() + "\",\"orderId\":\"" + order.orderId()
								+ "\",\"amount\":" + order.amount() + "}"))
				.andExpect(status().isOk());
	}

	private URI uri(String key) {
		return uri(key, "");
	}

	private URI uri(String key, String query) {
		return URI.create("http://localhost:" + port + "/api/public/widget/" + key + "/events" + query);
	}

	private int httpStatus(String key) throws Exception {
		// EventSource 처럼 Accept: text/event-stream 만 보낸다(에러 본문 JSON 과 협상이 어긋나도 상태 코드는 지켜야 한다)
		return http.send(HttpRequest.newBuilder(uri(key)).header("Accept", "text/event-stream")
						.timeout(Duration.ofSeconds(5)).GET().build(),
				HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	/** SSE 를 열어 이벤트(event · id · data)를 큐로 모은다. 스트림이 끝나면 {@link #END}. */
	private BlockingQueue<Map<String, String>> open(String key, String lastEventId) throws Exception {
		return open(key, "", lastEventId);
	}

	/** query 는 "?lastEventId=…" 처럼 앞의 ? 까지 넣는다. lastEventId 는 Last-Event-ID 헤더. */
	private BlockingQueue<Map<String, String>> open(String key, String query, String lastEventId) throws Exception {
		HttpRequest.Builder req = HttpRequest.newBuilder(uri(key, query)).header("Accept", "text/event-stream").GET();
		if (lastEventId != null) {
			req.header("Last-Event-ID", lastEventId);
		}
		HttpResponse<Stream<String>> res = http.send(req.build(), HttpResponse.BodyHandlers.ofLines());
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
		Stream<String> lines = res.body();
		streams.add(lines);
		BlockingQueue<Map<String, String>> queue = new LinkedBlockingQueue<>();
		Thread.ofVirtual().start(() -> {
			Map<String, String> current = new java.util.HashMap<>();
			try {
				lines.forEach(line -> {
					if (line.isEmpty()) {
						if (current.containsKey("event") || current.containsKey("data")) {
							queue.add(Map.copyOf(current));
						}
						current.clear();
					} else if (!line.startsWith(":")) {
						int colon = line.indexOf(':');
						String field = colon < 0 ? line : line.substring(0, colon);
						String value = colon < 0 ? "" : line.substring(colon + 1).stripLeading();
						current.merge(field, value, (a, b) -> a + "\n" + b);
					}
				});
			} catch (UncheckedIOException ignored) {
				// 연결이 닫혔다
			}
			queue.add(END);
		});
		return queue;
	}

	private static Map<String, String> next(BlockingQueue<Map<String, String>> queue) throws InterruptedException {
		Map<String, String> m = queue.poll(5, TimeUnit.SECONDS);
		assertThat(m).as("5초 안에 이벤트가 와야 한다").isNotNull();
		return m;
	}

	private void awaitConnections(long channelId, int expected) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (widgetEventService.connectionCount(channelId) != expected && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(widgetEventService.connectionCount(channelId)).isEqualTo(expected);
	}
}
