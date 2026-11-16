package com.livestream.livestream.clip;

import com.livestream.livestream.common.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 비공개 클립 재생 토큰 (DESIGN §4.16 클립 video-token): HS256, aud=clip-video, sub=clipId, 5분.
 * 서명 키는 {@code "clip-video-token:" + app.jwt.secret} 의 SHA-256 — access 토큰 · 다시보기 재생 토큰과 모두 분리한다
 * (같은 키면 sub 가 같은 숫자인 다른 종류의 토큰이 통과할 수 있다). 구조는 다시보기의 VideoTokenService 와 같다.
 */
@Component
public class ClipVideoTokenService {

	static final Duration TTL = Duration.ofMinutes(5);
	private static final String AUDIENCE = "clip-video";

	private final SecretKey key;
	private final Clock clock;

	@Autowired
	public ClipVideoTokenService(AppProperties props) {
		this(props, Clock.systemUTC());
	}

	ClipVideoTokenService(AppProperties props, Clock clock) {
		this.key = Keys.hmacShaKeyFor(derive(props.jwt().secret()));
		this.clock = clock;
	}

	public String issue(Long clipId) {
		Instant now = clock.instant();
		return Jwts.builder()
				.subject(String.valueOf(clipId))
				.audience().add(AUDIENCE).and()
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(TTL)))
				.signWith(key)
				.compact();
	}

	/** 토큰이 이 클립용이고 만료 전이면 true. */
	public boolean isValidFor(String token, Long clipId) {
		if (token == null || token.isBlank()) {
			return false;
		}
		try {
			Claims claims = Jwts.parser().verifyWith(key).requireAudience(AUDIENCE)
					.clock(() -> Date.from(clock.instant())).build()
					.parseSignedClaims(token).getPayload();
			return String.valueOf(clipId).equals(claims.getSubject());
		} catch (JwtException | IllegalArgumentException e) {
			return false;
		}
	}

	private static byte[] derive(String secret) {
		try {
			return MessageDigest.getInstance("SHA-256")
					.digest(("clip-video-token:" + secret).getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
