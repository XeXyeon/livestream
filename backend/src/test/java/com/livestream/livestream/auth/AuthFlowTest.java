package com.livestream.livestream.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.channel.Category;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.common.config.AppProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** AUTH-1 통합 테스트: 가입 → 로그인 → /members/me → refresh 회전 → 로그아웃 후 401, 중복 409 (DESIGN §4.7). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowTest {

	private static final String PASSWORD = "password123";

	@Autowired MockMvc mvc;
	@Autowired ChannelRepository channelRepository;
	@Autowired AppProperties props;
	@Autowired EntityManager em;
	@Autowired TransactionTemplate tx;

	// ---------- 흐름 ----------

	@Test
	void 가입_로그인_내정보_회전_로그아웃_후_refresh는_401() throws Exception {
		String email = uniqueEmail();
		String nickname = uniqueNickname();

		// 가입 → 201 {memberId}
		MvcResult signup = signup(email, nickname).andExpect(status().isCreated())
				.andExpect(jsonPath("$.memberId").isNumber()).andReturn();
		long memberId = ((Number) JsonPath.read(signup.getResponse().getContentAsString(), "$.memberId")).longValue();

		// 로그인 → access 본문 + refresh HttpOnly 쿠키
		MvcResult login = login(email, PASSWORD).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isString())
				.andExpect(jsonPath("$.member.id").value(memberId))
				.andExpect(jsonPath("$.member.nickname").value(nickname))
				.andExpect(jsonPath("$.member.channelId").isNumber()).andReturn();
		String setCookie = login.getResponse().getHeader("Set-Cookie");
		assertThat(setCookie).startsWith("refresh_token=")
				.contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth", "Max-Age=" + Duration.ofDays(14).toSeconds());
		String access = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
		String refresh0 = refreshOf(login);

		// /members/me
		mvc.perform(get("/api/members/me").header("Authorization", "Bearer " + access))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(memberId))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.nickname").value(nickname))
				.andExpect(jsonPath("$.channelId").value(((Number) JsonPath.read(login.getResponse().getContentAsString(), "$.member.channelId")).intValue()))
				.andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.endsWith("Z")));

		// refresh 회전: 새 access + 새 refresh 쿠키
		MvcResult refreshed = refresh(refresh0).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isString())
				.andExpect(jsonPath("$.member.id").value(memberId)).andReturn();
		String refresh1 = refreshOf(refreshed);
		assertThat(refresh1).isNotEqualTo(refresh0);
		mvc.perform(get("/api/members/me")
				.header("Authorization", "Bearer " + (String) JsonPath.read(refreshed.getResponse().getContentAsString(), "$.accessToken")))
				.andExpect(status().isOk());

		// 로그아웃 → 쿠키 삭제, 이후 어떤 refresh도 401
		MvcResult logout = mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", refresh1)))
				.andExpect(status().isOk()).andReturn();
		assertThat(logout.getResponse().getHeader("Set-Cookie")).startsWith("refresh_token=;").contains("Max-Age=0", "Path=/api/auth");
		refresh(refresh1).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
		refresh(refresh0).andExpect(status().isUnauthorized());
	}

	@Test
	void 가입하면_채널이_자동_생성된다() throws Exception {
		String nickname = uniqueNickname();
		String email = uniqueEmail();
		signup(email, nickname).andExpect(status().isCreated());
		long channelId = ((Number) JsonPath.read(login(email, PASSWORD).andReturn().getResponse().getContentAsString(),
				"$.member.channelId")).longValue();

		tx.executeWithoutResult(s -> {
			Channel channel = channelRepository.findById(channelId).orElseThrow();
			assertThat(channel.getName()).isEqualTo(nickname);
			assertThat(channel.getTitle()).isEqualTo(nickname + "의 방송");
			assertThat(channel.getCategory()).isEqualTo(Category.ETC);
			assertThat(channel.isAutoPublishRecording()).isTrue();
			assertThat(channel.getStreamKey()).matches("[0-9a-f]{32}");
			assertThat(channel.getWidgetKey()).matches("[0-9a-f]{32}").isNotEqualTo(channel.getStreamKey());
			assertThat(channel.getMember().getEmail()).isEqualTo(email);
		});
	}

	// ---------- 가입 · 로그인 오류 ----------

	@Test
	void 이메일_중복은_409() throws Exception {
		String email = uniqueEmail();
		signup(email, uniqueNickname()).andExpect(status().isCreated());
		signup(email, uniqueNickname()).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
		// 대소문자만 다른 이메일도 같은 이메일
		signup(email.toUpperCase(), uniqueNickname()).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
	}

	@Test
	void 닉네임_중복은_409() throws Exception {
		String nickname = uniqueNickname();
		signup(uniqueEmail(), nickname).andExpect(status().isCreated());
		signup(uniqueEmail(), nickname).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
	}

	@Test
	void 가입_입력값_검증은_400() throws Exception {
		signup("not-an-email", uniqueNickname()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json(uniqueEmail(), "short", uniqueNickname())))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		signup(uniqueEmail(), "a").andExpect(status().isBadRequest());
		signup(uniqueEmail(), "x".repeat(21)).andExpect(status().isBadRequest());
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 로그인_실패는_이메일과_비밀번호_어느_쪽인지_알려주지_않는다() throws Exception {
		String email = uniqueEmail();
		signup(email, uniqueNickname()).andExpect(status().isCreated());
		String wrongPassword = login(email, "wrong-password").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("LOGIN_FAILED")).andReturn().getResponse().getContentAsString();
		String unknownEmail = login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("LOGIN_FAILED")).andReturn().getResponse().getContentAsString();
		assertThat(wrongPassword).isEqualTo(unknownEmail);
	}

	// ---------- access 토큰 ----------

	@Test
	void 토큰_없이_보호_경로는_401_UNAUTHENTICATED() throws Exception {
		mvc.perform(get("/api/members/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/channels/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void 만료된_access는_TOKEN_EXPIRED_위조는_TOKEN_INVALID() throws Exception {
		byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
		String expired = Jwts.builder().subject("1").claim("nickname", "x").claim("channelId", 1)
				.issuedAt(Date.from(Instant.now().minusSeconds(3600)))
				.expiration(Date.from(Instant.now().minusSeconds(60)))
				.signWith(Keys.hmacShaKeyFor(secret)).compact();
		mvc.perform(get("/api/members/me").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

		String forged = Jwts.builder().subject("1").claim("nickname", "x").claim("channelId", 1)
				.expiration(Date.from(Instant.now().plusSeconds(600)))
				.signWith(Keys.hmacShaKeyFor("another-secret-key-another-secret-key-0123".getBytes(StandardCharsets.UTF_8))).compact();
		mvc.perform(get("/api/members/me").header("Authorization", "Bearer " + forged))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
		mvc.perform(get("/api/members/me").header("Authorization", "Bearer garbage"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
	}

	// ---------- refresh 회전 · 유예 · 재사용 의심 ----------

	@Test
	void refresh_쿠키가_없으면_401_쿠키삭제() throws Exception {
		MvcResult result = mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("TOKEN_INVALID")).andReturn();
		assertThat(result.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
		refresh("no-such-token").andExpect(status().isUnauthorized());
	}

	@Test
	void 회전_유예_안에서는_같은_토큰으로_다시_갱신된다_탭_두_개_동시_갱신() throws Exception {
		String refresh0 = signupAndLogin();
		String refresh1 = refreshOf(refresh(refresh0).andExpect(status().isOk()).andReturn());
		// 두 번째 탭이 같은(이미 회전된) 토큰으로 곧바로 갱신
		String refresh2 = refreshOf(refresh(refresh0).andExpect(status().isOk()).andReturn());
		assertThat(refresh2).isNotIn(refresh0, refresh1);
		// 두 탭이 받은 새 토큰 모두 살아 있다
		refresh(refresh1).andExpect(status().isOk());
		refresh(refresh2).andExpect(status().isOk());
	}

	@Test
	void 유예를_넘겨_회전된_토큰이_오면_재사용_의심으로_그_회원의_토큰을_전부_폐기한다() throws Exception {
		String refresh0 = signupAndLogin();
		String refresh1 = refreshOf(refresh(refresh0).andExpect(status().isOk()).andReturn());
		ageRevocation(refresh0, props.jwt().refreshRotationGraceSeconds() + 5);

		refresh(refresh0).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
		// 정상으로 보이던 최신 토큰도 폐기됐다
		refresh(refresh1).andExpect(status().isUnauthorized());
	}

	@Test
	void 로그아웃하면_유예_중인_회전된_토큰도_쓸_수_없다() throws Exception {
		String refresh0 = signupAndLogin();
		String refresh1 = refreshOf(refresh(refresh0).andExpect(status().isOk()).andReturn());
		mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", refresh1))).andExpect(status().isOk());
		refresh(refresh0).andExpect(status().isUnauthorized());
	}

	@Test
	void 로그아웃은_다른_기기의_토큰도_폐기한다() throws Exception {
		String email = uniqueEmail();
		signup(email, uniqueNickname()).andExpect(status().isCreated());
		String device1 = refreshOf(login(email, PASSWORD).andReturn());
		String device2 = refreshOf(login(email, PASSWORD).andReturn());
		mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", device1))).andExpect(status().isOk());
		refresh(device2).andExpect(status().isUnauthorized());
	}

	@Test
	void 로그아웃은_쿠키가_없어도_200이다() throws Exception {
		mvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
	}

	@Test
	void 만료된_refresh는_401_TOKEN_EXPIRED() throws Exception {
		String refresh0 = signupAndLogin();
		tx.executeWithoutResult(s -> em.createQuery("update RefreshToken t set t.expiresAt = :ts where t.tokenHash = :h")
				.setParameter("ts", Instant.now().minusSeconds(5)).setParameter("h", AuthService.hash(refresh0)).executeUpdate());
		refresh(refresh0).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}

	@Test
	void DB에는_refresh_토큰_원문이_아니라_SHA256_해시만_저장된다() throws Exception {
		String refresh0 = signupAndLogin();
		Long count = tx.execute(s -> em.createQuery("select count(t) from RefreshToken t where t.tokenHash = :h", Long.class)
				.setParameter("h", refresh0).getSingleResult());
		assertThat(count).isZero();
		assertThat(AuthService.hash(refresh0)).hasSize(64);
	}

	// ---------- 리뷰에서 보강한 엣지케이스 ----------

	@Test
	void 같은_refresh_토큰으로_동시에_갱신해도_둘_다_성공하고_첫_회전_기록은_덮어쓰이지_않는다() throws Exception {
		String refresh0 = signupAndLogin();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return refresh(refresh0).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			for (Future<Integer> f : futures) {
				assertThat(f.get(20, TimeUnit.SECONDS)).isEqualTo(200);
			}
		} finally {
			pool.shutdownNow();
		}
		Long replacedBy = tx.execute(s -> em.createQuery("select t.replacedById from RefreshToken t where t.tokenHash = :h", Long.class)
				.setParameter("h", AuthService.hash(refresh0)).getSingleResult());
		assertThat(replacedBy).isNotNull();
	}

	@Test
	void 동시에_같은_이메일로_가입하면_하나만_201_나머지는_409() throws Exception {
		String email = uniqueEmail();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String nickname = uniqueNickname();
				futures.add(pool.submit(() -> {
					start.await();
					return signup(email, nickname).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : futures) {
				statuses.add(f.get(20, TimeUnit.SECONDS));
			}
			assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 로그아웃한_회전_토큰으로_로그아웃하면_최신_토큰도_폐기된다() throws Exception {
		String refresh0 = signupAndLogin();
		String refresh1 = refreshOf(refresh(refresh0).andExpect(status().isOk()).andReturn());
		mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", refresh0))).andExpect(status().isOk());
		refresh(refresh1).andExpect(status().isUnauthorized());
	}

	@Test
	void 재사용_의심_401에도_쿠키_삭제_헤더가_붙는다() throws Exception {
		String refresh0 = signupAndLogin();
		refresh(refresh0).andExpect(status().isOk());
		ageRevocation(refresh0, props.jwt().refreshRotationGraceSeconds() + 5);
		MvcResult result = refresh(refresh0).andExpect(status().isUnauthorized()).andReturn();
		assertThat(result.getResponse().getHeader("Set-Cookie")).startsWith("refresh_token=;").contains("Max-Age=0");
	}

	@Test
	void 유예_경계_유예_직전은_통과_직후는_재사용_의심() throws Exception {
		String refresh0 = signupAndLogin();
		refresh(refresh0).andExpect(status().isOk());
		ageRevocation(refresh0, props.jwt().refreshRotationGraceSeconds() - 3);
		refresh(refresh0).andExpect(status().isOk());
		ageRevocation(refresh0, props.jwt().refreshRotationGraceSeconds() + 3);
		refresh(refresh0).andExpect(status().isUnauthorized());
	}

	@Test
	void 입력_경계_공백_닉네임_72바이트_초과_비밀번호_긴_이메일() throws Exception {
		signup(uniqueEmail(), " a ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		// 한글 25자 = 75바이트 → BCrypt 72바이트 한도 초과
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json(uniqueEmail(), "가".repeat(25), uniqueNickname())))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		// 한글 24자 = 72바이트는 허용
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json(uniqueEmail(), "가".repeat(24), uniqueNickname()))).andExpect(status().isCreated());
		signup("a".repeat(250) + "@example.com", uniqueNickname()).andExpect(status().isBadRequest());
	}

	@Test
	void 공개_경로는_토큰이_없거나_만료돼도_401이_아니다() throws Exception {
		byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
		String expired = Jwts.builder().subject("1").expiration(Date.from(Instant.now().minusSeconds(60)))
				.signWith(Keys.hmacShaKeyFor(secret)).compact();
		// 아직 컨트롤러가 없어 404지만, Security가 401로 막지 않는지(공개 매처가 열려 있는지)를 확인한다.
		mvc.perform(get("/api/broadcasts")).andExpect(status().isNotFound());
		mvc.perform(get("/api/broadcasts/1").header("Authorization", "Bearer " + expired)).andExpect(status().isNotFound());
		mvc.perform(get("/api/channels/1")).andExpect(status().isNotFound());
		mvc.perform(get("/api/recordings")).andExpect(status().isNotFound());
		// 로그인이 필요한 경로는 그대로 401
		mvc.perform(get("/api/channels/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/broadcasts/1/end")).andExpect(status().isUnauthorized());
	}

	@Test
	void 잘못된_Content_Type은_500이_아니라_415() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("x"))
				.andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	// ---------- helpers ----------

	private String signupAndLogin() throws Exception {
		String email = uniqueEmail();
		signup(email, uniqueNickname()).andExpect(status().isCreated());
		return refreshOf(login(email, PASSWORD).andExpect(status().isOk()).andReturn());
	}

	private void ageRevocation(String rawRefresh, long seconds) {
		tx.executeWithoutResult(s -> em.createQuery("update RefreshToken t set t.revokedAt = :ts where t.tokenHash = :h")
				.setParameter("ts", Instant.now().minusSeconds(seconds)).setParameter("h", AuthService.hash(rawRefresh)).executeUpdate());
	}

	private ResultActions signup(String email, String nickname) throws Exception {
		return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content(json(email, PASSWORD, nickname)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private ResultActions refresh(String rawRefresh) throws Exception {
		return mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", rawRefresh)));
	}

	private static String refreshOf(MvcResult result) {
		Cookie cookie = result.getResponse().getCookie("refresh_token");
		assertThat(cookie).isNotNull();
		return cookie.getValue();
	}

	private static String json(String email, String password, String nickname) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"nickname\":\"" + nickname + "\"}";
	}

	private static String uniqueEmail() {
		return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.com";
	}

	private static String uniqueNickname() {
		return "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
