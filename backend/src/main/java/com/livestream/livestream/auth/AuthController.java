package com.livestream.livestream.auth;

import com.livestream.livestream.auth.dto.AuthResponse;
import com.livestream.livestream.auth.dto.LoginRequest;
import com.livestream.livestream.auth.dto.SignupRequest;
import com.livestream.livestream.auth.dto.SignupResponse;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "회원가입 · 로그인 · 갱신 · 로그아웃")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	static final String REFRESH_COOKIE = "refresh_token";
	/** 쿠키를 인증 API에만 보낸다(DESIGN §4.7). */
	private static final String COOKIE_PATH = "/api/auth";

	private final AuthService authService;
	private final AppProperties props;

	@Operation(summary = "회원가입", description = "채널이 함께 자동 생성된다. 이메일 · 닉네임 중복은 409. IP당 시도 횟수 초과는 429.")
	@PostMapping("/signup")
	public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new SignupResponse(authService.signup(request, http.getRemoteAddr())));
	}

	@Operation(summary = "로그인", description = "access 토큰은 본문, refresh 토큰은 HttpOnly 쿠키로 내려간다. IP+이메일당 실패 횟수 초과는 429(Retry-After).")
	@PostMapping("/login")
	public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
		AuthResult result = authService.login(request, http.getRemoteAddr());
		return withRefreshCookie(result);
	}

	@Operation(summary = "access 토큰 갱신", description = "refresh 쿠키를 회전한다. 실패하면 401 + 쿠키 삭제.")
	@PostMapping("/refresh")
	public ResponseEntity<AuthResponse> refresh(
			@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletResponse response) {
		try {
			return withRefreshCookie(authService.refresh(refreshToken));
		} catch (ApiException e) {
			response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie().toString());
			throw e;
		}
	}

	@Operation(summary = "로그아웃", description = "그 회원의 refresh 토큰을 전부 폐기한다. access 토큰은 만료까지 유효.")
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
		authService.logout(refreshToken);
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expiredCookie().toString()).build();
	}

	private ResponseEntity<AuthResponse> withRefreshCookie(AuthResult result) {
		ResponseCookie cookie = baseCookie(result.refreshToken())
				.maxAge(Duration.ofDays(props.jwt().refreshDays())).build();
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(result.response());
	}

	private ResponseCookie expiredCookie() {
		return baseCookie("").maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
		return ResponseCookie.from(REFRESH_COOKIE, value)
				.httpOnly(true).secure(true).sameSite("Strict").path(COOKIE_PATH);
	}
}
