package com.livestream.livestream.donation;

import com.livestream.livestream.common.config.AppProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Toss 결제 승인 API 호출 ({@code POST /v1/payments/confirm}, Basic 인증 = base64(secretKey + ":"), 타임아웃 5초).
 * <ul>
 *   <li>{@code Idempotency-Key: orderId}: 타임아웃 뒤 같은 confirm 을 다시 보내도 Toss 가 처음 결과를 돌려준다(이중 승인 · 이미
 *       처리됨 오류 방지). 그래서 결과를 모르는 실패(UNKNOWN)는 후원을 PENDING 으로 두고 재시도를 받는다.</li>
 *   <li>시크릿 키는 로그 · 예외 메시지에 넣지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class RestTossPaymentsClient implements TossPaymentsClient {

	static final String DEFAULT_BASE_URL = "https://api.tosspayments.com";
	static final Duration TIMEOUT = Duration.ofSeconds(5);
	private static final int MESSAGE_MAX = 200;
	private static final java.util.Set<String> TRANSIENT_CODES = java.util.Set.of(
			"ALREADY_PROCESSED_PAYMENT", "IDEMPOTENT_REQUEST_PROCESSING", "PROVIDER_ERROR",
			"FAILED_INTERNAL_SYSTEM_PROCESSING", "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING", "UNKNOWN_PAYMENT_ERROR");

	private final RestClient restClient;
	private final String secretKey;
	private final ObjectMapper json = JsonMapper.builder().build();

	@Autowired
	public RestTossPaymentsClient(AppProperties props) {
		this(DEFAULT_BASE_URL, props.toss().secretKey(), TIMEOUT);
	}

	/** 테스트용: 주소 · 타임아웃을 바꾼다. */
	RestTossPaymentsClient(String baseUrl, String secretKey, Duration timeout) {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(timeout).build());
		factory.setReadTimeout(timeout);
		this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
		this.secretKey = secretKey;
	}

	@Override
	public Result confirm(String paymentKey, String orderId, long amount) {
		if (secretKey == null || secretKey.isBlank()) {
			log.error("Toss 시크릿 키가 설정되지 않았습니다(TOSS_SECRET_KEY)");
			return new Result(Outcome.UNKNOWN, null, "결제 설정이 되어 있지 않습니다");
		}
		String body;
		try {
			body = json.writeValueAsString(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount));
		} catch (JacksonException e) {
			return new Result(Outcome.UNKNOWN, null, null);
		}
		String auth = "Basic " + Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
		try {
			return restClient.post().uri("/v1/payments/confirm")
					.header(HttpHeaders.AUTHORIZATION, auth)
					.header("Idempotency-Key", orderId)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.exchange((request, response) -> interpret(response.getStatusCode().value(), readBody(response.getBody())),
							true);
		} catch (RestClientException e) {
			// 타임아웃 · 연결 실패: Toss 쪽에서 승인됐을 수 있다.
			log.warn("Toss 승인 호출 실패(결과 모름) orderId={}: {}", orderId, e.getClass().getSimpleName());
			return new Result(Outcome.UNKNOWN, null, null);
		}
	}

	Result interpret(int status, String responseBody) {
		JsonNode node = parse(responseBody);
		String code = text(node, "code");
		String message = truncate(text(node, "message"));
		if (status >= 200 && status < 300) {
			String paymentStatus = text(node, "status");
			if ("DONE".equals(paymentStatus)) {
				return Result.approved();
			}
			// 가상계좌 입금 대기(WAITING_FOR_DEPOSIT) 등: 아직 돈이 들어오지 않았다.
			log.warn("Toss 승인 응답이 DONE 이 아님: status={}", paymentStatus);
			return new Result(Outcome.UNKNOWN, paymentStatus, "결제가 완료되지 않았습니다");
		}
		if (status >= 400 && status < 500 && !isTransient(status, code)) {
			return new Result(Outcome.REJECTED, code, message);
		}
		log.warn("Toss 승인 서버 오류 status={} code={}", status, code);
		return new Result(Outcome.UNKNOWN, code, message);
	}

	/**
	 * 4xx 라도 결제가 끝났거나 끝날 수 있는 응답은 거절로 굳히지 않는다(후원을 FAILED 로 닫으면 돈은 나갔는데 복구할 수 없다).
	 * 401 · 403 은 우리 키 설정 문제, 409 · 429 는 처리 중 · 과다 요청, ALREADY_PROCESSED_PAYMENT 는 이미 승인됨.
	 */
	static boolean isTransient(int status, String code) {
		return status == 401 || status == 403 || status == 409 || status == 429
				|| (code != null && TRANSIENT_CODES.contains(code));
	}

	private static String readBody(java.io.InputStream in) {
		try (in) {
			return new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	private JsonNode parse(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return json.readTree(body);
		} catch (JacksonException e) {
			return null;
		}
	}

	private static String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value != null && value.isString() ? value.asString() : null;
	}

	private static String truncate(String s) {
		if (s == null || s.codePointCount(0, s.length()) <= MESSAGE_MAX) {
			return s;
		}
		return s.substring(0, s.offsetByCodePoints(0, MESSAGE_MAX));
	}
}
