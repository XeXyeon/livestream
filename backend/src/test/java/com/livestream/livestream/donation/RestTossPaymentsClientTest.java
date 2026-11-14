package com.livestream.livestream.donation;

import static org.assertj.core.api.Assertions.assertThat;

import com.livestream.livestream.donation.TossPaymentsClient.Outcome;
import com.livestream.livestream.donation.TossPaymentsClient.Result;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 HTTP 로 Toss 승인 요청 모양(Basic 인증 · Idempotency-Key · 본문)과 응답 해석을 확인한다. */
class RestTossPaymentsClientTest {

	private HttpServer server;
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>();
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private volatile int responseStatus;
	private volatile String responseBody;
	private volatile long delayMillis;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/payments/confirm", exchange -> {
			lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			lastIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
			lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			try {
				Thread.sleep(delayMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(responseStatus, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private RestTossPaymentsClient client(String secret) {
		return new RestTossPaymentsClient("http://127.0.0.1:" + server.getAddress().getPort(), secret, Duration.ofMillis(500));
	}

	@Test
	void 승인_요청은_Basic_인증과_주문번호_멱등_키를_붙인다() {
		respond(200, "{\"status\":\"DONE\",\"orderId\":\"o-1\",\"totalAmount\":5000}");
		Result r = client("test_sk_abc").confirm("pk-1", "o-1", 5000);

		assertThat(r.outcome()).isEqualTo(Outcome.APPROVED);
		assertThat(lastAuth.get()).isEqualTo("Basic " + Base64.getEncoder().encodeToString("test_sk_abc:".getBytes(StandardCharsets.UTF_8)));
		assertThat(lastIdempotencyKey.get()).isEqualTo("o-1");
		assertThat(lastBody.get()).contains("\"paymentKey\":\"pk-1\"").contains("\"orderId\":\"o-1\"").contains("\"amount\":5000");
	}

	@Test
	void 응답_해석() {
		respond(200, "{\"status\":\"WAITING_FOR_DEPOSIT\"}");
		assertThat(client("sk").confirm("pk", "o", 1000).outcome()).isEqualTo(Outcome.UNKNOWN);

		respond(400, "{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"카드사에서 거절했습니다\"}");
		Result rejected = client("sk").confirm("pk", "o", 1000);
		assertThat(rejected).isEqualTo(new Result(Outcome.REJECTED, "REJECT_CARD_COMPANY", "카드사에서 거절했습니다"));

		respond(500, "{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"내부 오류\"}");
		assertThat(client("sk").confirm("pk", "o", 1000).outcome()).isEqualTo(Outcome.UNKNOWN);

		respond(400, "not json");
		assertThat(client("sk").confirm("pk", "o", 1000)).isEqualTo(new Result(Outcome.REJECTED, null, null));
	}

	@Test
	void 일시적이거나_이미_처리된_4xx_는_거절로_굳히지_않는다() {
		for (int status : new int[] {401, 403, 409, 429}) {
			respond(status, "{\"code\":\"SOMETHING\",\"message\":\"m\"}");
			assertThat(client("sk").confirm("pk", "o", 1000).outcome()).as("status %d", status).isEqualTo(Outcome.UNKNOWN);
		}
		for (String code : new String[] {"ALREADY_PROCESSED_PAYMENT", "IDEMPOTENT_REQUEST_PROCESSING", "PROVIDER_ERROR",
				"FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "UNKNOWN_PAYMENT_ERROR"}) {
			respond(400, "{\"code\":\"" + code + "\",\"message\":\"m\"}");
			assertThat(client("sk").confirm("pk", "o", 1000).outcome()).as(code).isEqualTo(Outcome.UNKNOWN);
		}
		respond(404, "{\"code\":\"NOT_FOUND_PAYMENT_SESSION\",\"message\":\"만료\"}");
		assertThat(client("sk").confirm("pk", "o", 1000).outcome()).isEqualTo(Outcome.REJECTED);
	}

	@Test
	void 타임아웃과_키_없음은_결과_모름() {
		respond(200, "{\"status\":\"DONE\"}");
		delayMillis = 1500;
		assertThat(client("sk").confirm("pk", "o", 1000).outcome()).isEqualTo(Outcome.UNKNOWN);

		delayMillis = 0;
		assertThat(client("").confirm("pk", "o", 1000).outcome()).isEqualTo(Outcome.UNKNOWN);
		assertThat(client(null).confirm("pk", "o", 1000).outcome()).isEqualTo(Outcome.UNKNOWN);
	}

	private void respond(int status, String body) {
		this.responseStatus = status;
		this.responseBody = body;
	}
}
