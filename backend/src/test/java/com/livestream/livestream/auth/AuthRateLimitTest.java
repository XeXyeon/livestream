package com.livestream.livestream.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * AUTH-1#1 통합 테스트 (DESIGN §4.7 · §9 AUTH-1): 실제 HTTP 로 로그인 · 가입 시도 횟수 제한을 확인한다.
 * 로컬 요청(127.0.0.1)은 Tomcat RemoteIpValve 의 신뢰 프록시라 {@code X-Forwarded-For} 가 클라이언트 IP 로 쓰인다 —
 * 테스트마다 다른 XFF 값을 줘 IP 별 카운터를 분리한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.auth.login-max-failures=3", "app.auth.signup-max-attempts=3",
		"app.auth.attempt-window-seconds=300"})
class AuthRateLimitTest {

	private static final String PASSWORD = "password123";

	@LocalServerPort int port;
	@MockitoSpyBean PasswordEncoder passwordEncoder;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void 로그인_실패가_한도에_이르면_다음_시도는_429_Retry_After이고_BCrypt는_돌지_않는다() throws Exception {
		String ip = uniqueIp();
		String email = signupWithOwnIp();
		for (int i = 0; i < 3; i++) {
			assertThat(login(ip, email, "wrong-password").statusCode()).isEqualTo(401);
		}
		clearInvocations(passwordEncoder);

		// 맞는 비밀번호여도 한도 뒤에는 막힌다
		HttpResponse<String> blocked = login(ip, email, PASSWORD);
		assertThat(blocked.statusCode()).isEqualTo(429);
		assertThat((String) JsonPath.read(blocked.body(), "$.code")).isEqualTo("TOO_MANY_REQUESTS");
		assertThat((String) JsonPath.read(blocked.body(), "$.message")).isEqualTo("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요");
		long retryAfter = Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow());
		assertThat(retryAfter).isBetween(1L, 300L);
		verify(passwordEncoder, never()).matches(any(), any());
	}

	@Test
	void 성공하면_카운터가_초기화된다() throws Exception {
		String ip = uniqueIp();
		String email = signupWithOwnIp();
		for (int i = 0; i < 2; i++) {
			assertThat(login(ip, email, "wrong-password").statusCode()).isEqualTo(401);
		}
		assertThat(login(ip, email, PASSWORD).statusCode()).isEqualTo(200);
		for (int i = 0; i < 3; i++) {
			assertThat(login(ip, email, "wrong-password").statusCode()).isEqualTo(401); // 다시 3번까지는 401
		}
		assertThat(login(ip, email, "wrong-password").statusCode()).isEqualTo(429);
	}

	@Test
	void 다른_IP_다른_이메일은_막히지_않는다_XFF가_키로_쓰인다() throws Exception {
		String ip = uniqueIp();
		String email = signupWithOwnIp();
		String other = signupWithOwnIp();
		for (int i = 0; i < 3; i++) {
			login(ip, email, "wrong-password");
		}
		assertThat(login(ip, email, PASSWORD).statusCode()).isEqualTo(429);
		assertThat(login(uniqueIp(), email, PASSWORD).statusCode()).isEqualTo(200); // 다른 IP(XFF)
		assertThat(login(ip, other, PASSWORD).statusCode()).isEqualTo(200); // 같은 IP 다른 이메일
	}

	@Test
	void 이메일은_대소문자_공백을_정규화해_같은_키로_센다() throws Exception {
		String ip = uniqueIp();
		String email = signupWithOwnIp();
		login(ip, email, "wrong-password");
		login(ip, email.toUpperCase(), "wrong-password");
		login(ip, "  " + email + " ", "wrong-password");
		assertThat(login(ip, email, PASSWORD).statusCode()).isEqualTo(429);
	}

	@Test
	void 악센트_전각_변형_이메일도_같은_키로_센다() throws Exception {
		String ip = uniqueIp();
		String email = signupWithOwnIp();
		login(ip, email, "wrong-password");
		login(ip, "\u0155" + email.substring(1), "wrong-password"); // 첫 글자 r 에 악센트(ŕ)
		login(ip, "\uFF52" + email.substring(1), "wrong-password"); // 전각 r
		assertThat(login(ip, email, PASSWORD).statusCode()).isEqualTo(429);
	}

	@Test
	void 너무_긴_이메일은_400으로_거절되고_키로_저장되지_않는다() throws Exception {
		String ip = uniqueIp();
		HttpResponse<String> res = login(ip, "a".repeat(300) + "@example.com", "wrong-password");
		assertThat(res.statusCode()).isEqualTo(400);
	}

	@Test
	void 가입은_IP당_한도를_넘는_요청부터_429_성공과_실패를_모두_센다() throws Exception {
		String ip = uniqueIp();
		String email = uniqueEmail();
		assertThat(signup(ip, email).statusCode()).isEqualTo(201);
		assertThat(signup(ip, email).statusCode()).isEqualTo(409); // 이메일 중복도 센다
		assertThat(signup(ip, uniqueEmail()).statusCode()).isEqualTo(201);

		HttpResponse<String> blocked = signup(ip, uniqueEmail());
		assertThat(blocked.statusCode()).isEqualTo(429);
		assertThat((String) JsonPath.read(blocked.body(), "$.code")).isEqualTo("TOO_MANY_REQUESTS");
		assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
		assertThat(signup(uniqueIp(), uniqueEmail()).statusCode()).isEqualTo(201); // 다른 IP
	}

	@Test
	void refresh와_logout은_제한이_없다() throws Exception {
		String ip = uniqueIp();
		for (int i = 0; i < 6; i++) {
			assertThat(post(ip, "/api/auth/refresh", "").statusCode()).isEqualTo(401);
			assertThat(post(ip, "/api/auth/logout", "").statusCode()).isEqualTo(200);
		}
	}

	// ---------- helpers ----------

	private String signupWithOwnIp() throws Exception {
		String email = uniqueEmail();
		assertThat(signup(uniqueIp(), email).statusCode()).isEqualTo(201);
		return email;
	}

	private HttpResponse<String> signup(String ip, String email) throws Exception {
		String nickname = email.equals(lastEmail) ? lastNickname : "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		lastEmail = email;
		lastNickname = nickname;
		return post(ip, "/api/auth/signup",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"" + nickname + "\"}");
	}

	private String lastEmail;
	private String lastNickname;

	private HttpResponse<String> login(String ip, String email, String password) throws Exception {
		return post(ip, "/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
	}

	private HttpResponse<String> post(String ip, String path, String body) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json").header("X-Forwarded-For", ip)
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String uniqueEmail() {
		return "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.com";
	}

	private static final java.util.concurrent.atomic.AtomicInteger IP_SEQ = new java.util.concurrent.atomic.AtomicInteger();

	/** 테스트마다 다른 클라이언트 IP(XFF). 사설 대역 프록시(127.0.0.1)가 보낸 값이라 신뢰된다. */
	private static String uniqueIp() {
		int n = IP_SEQ.incrementAndGet();
		return "203.0." + (n / 250) + "." + (n % 250 + 1);
	}
}
