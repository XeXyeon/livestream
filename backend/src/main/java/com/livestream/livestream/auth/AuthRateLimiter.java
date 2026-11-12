package com.livestream.livestream.auth;

import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.error.TooManyRequestsException;
import java.text.Normalizer;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 로그인 · 가입 시도 횟수 제한 (DESIGN §4.7, AUTH-1#1). {@code ChatRateLimiter}와 같은 고정 창 · 인메모리 · 서버 1대 가정.
 * <ul>
 *   <li>로그인: 키 = IP + 정규화 이메일. 창 안 <b>실패</b>가 한도에 이르면 그 다음 시도부터 거절. 성공하면 그 키를 지운다.</li>
 *   <li>가입: 키 = IP. 성공 · 실패 불문 요청마다 센다. 한도를 넘는 요청부터 거절.</li>
 * </ul>
 * 로그인 키는 실패할 때만 생기므로 맵은 "창(5분) 안에 실패한 (IP, 이메일) 쌍" 수를 넘지 않고, 지난 창은 1분마다 지운다.
 */
@Component
public class AuthRateLimiter {

	private final Map<String, long[]> loginFailures = new ConcurrentHashMap<>();
	private final Map<String, long[]> signupAttempts = new ConcurrentHashMap<>();
	private final int loginMaxFailures;
	private final int signupMaxAttempts;
	private final long windowMillis;
	private final Clock clock;

	@Autowired
	public AuthRateLimiter(AppProperties props) {
		this(props.auth(), Clock.systemUTC());
	}

	AuthRateLimiter(AppProperties.Auth auth, Clock clock) {
		this.loginMaxFailures = auth.loginMaxFailures();
		this.signupMaxAttempts = auth.signupMaxAttempts();
		this.windowMillis = auth.attemptWindowSeconds() * 1000L;
		this.clock = clock;
	}

	/** 로그인 시도 전에 부른다(DB 조회 · BCrypt 전). 이 키의 실패가 이미 한도면 {@link TooManyRequestsException}. */
	public void checkLogin(String ip, String email) {
		String key = loginKey(ip, email);
		long now = clock.millis();
		long[] w = loginFailures.get(key);
		if (w != null && now - w[0] < windowMillis && w[1] >= loginMaxFailures) {
			throw new TooManyRequestsException(retryAfterSeconds(w[0], now));
		}
	}

	/** 비밀번호 · 이메일이 틀렸을 때(LOGIN_FAILED) 센다. */
	public void recordLoginFailure(String ip, String email) {
		long now = clock.millis();
		loginFailures.compute(loginKey(ip, email), (k, w) -> {
			if (w == null || now - w[0] >= windowMillis) {
				return new long[] {now, 1};
			}
			w[1]++;
			return w;
		});
	}

	/** 로그인에 성공하면 그 키의 카운터를 지운다. */
	public void clearLogin(String ip, String email) {
		loginFailures.remove(loginKey(ip, email));
	}

	/** 가입 요청을 세고, 이번 창에서 한도를 넘었으면 {@link TooManyRequestsException}. 검증 전에 부른다. */
	public void acquireSignup(String ip) {
		long now = clock.millis();
		long[][] snapshot = new long[1][];
		signupAttempts.compute(ip, (k, w) -> {
			if (w == null || now - w[0] >= windowMillis) {
				w = new long[] {now, 0};
			}
			if (w[1] < signupMaxAttempts) {
				w[1]++;
			}
			else {
				snapshot[0] = new long[] {w[0]};
			}
			return w;
		});
		if (snapshot[0] != null) {
			throw new TooManyRequestsException(retryAfterSeconds(snapshot[0][0], now));
		}
	}

	private long retryAfterSeconds(long windowStart, long now) {
		long remainingMillis = windowStart + windowMillis - now;
		return (remainingMillis + 999) / 1000;
	}

	/**
	 * 키의 이메일은 DB 비교(MySQL 기본 콜레이션 utf8mb4_0900_ai_ci: 악센트 · 전각 · 대소문자 무시)에 가깝게 접는다.
	 * 그렇지 않으면 "victim@x.com" / "vīctim@x.com" 변형으로 같은 계정의 제한 키를 돌려 쓸 수 있다.
	 */
	private static String loginKey(String ip, String email) {
		String folded = Normalizer.normalize(email, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
		return ip + '|' + folded;
	}

	/** 테스트용: 들고 있는 키 수. */
	int sizeForTest() {
		return loginFailures.size() + signupAttempts.size();
	}

	/** 지난 창은 지운다(맵이 계속 크지 않게). */
	@Scheduled(fixedDelay = 60_000)
	void purgeExpired() {
		long now = clock.millis();
		loginFailures.entrySet().removeIf(e -> now - e.getValue()[0] >= windowMillis);
		signupAttempts.entrySet().removeIf(e -> now - e.getValue()[0] >= windowMillis);
	}
}
