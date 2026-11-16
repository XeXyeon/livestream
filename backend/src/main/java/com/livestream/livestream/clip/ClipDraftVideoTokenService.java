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
 * 라이브 클립 초안 재생 토큰 (DESIGN §4.16 {@code GET /clip-drafts/{id}}, CLIP-3): HS256, aud=clip-draft-video, sub=draftId, 10분.
 * 서명 키는 {@code "clip-draft-video-token:" + app.jwt.secret} 의 SHA-256 — access · 다시보기 · 클립 재생 토큰과 모두 분리한다.
 * 구조는 {@link ClipVideoTokenService} 와 같다.
 */
@Component
public class ClipDraftVideoTokenService {

	static final Duration TTL = Duration.ofMinutes(10);
	private static final String AUDIENCE = "clip-draft-video";

	private final SecretKey key;
	private final Clock clock;

	@Autowired
	public ClipDraftVideoTokenService(AppProperties props) {
		this(props, Clock.systemUTC());
	}

	ClipDraftVideoTokenService(AppProperties props, Clock clock) {
		this.key = Keys.hmacShaKeyFor(derive(props.jwt().secret()));
		this.clock = clock;
	}

	public String issue(Long draftId) {
		Instant now = clock.instant();
		return Jwts.builder()
				.subject(String.valueOf(draftId))
				.audience().add(AUDIENCE).and()
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(TTL)))
				.signWith(key)
				.compact();
	}

	/** 토큰이 이 초안용이고 만료 전이면 true. */
	public boolean isValidFor(String token, Long draftId) {
		if (token == null || token.isBlank()) {
			return false;
		}
		try {
			Claims claims = Jwts.parser().verifyWith(key).requireAudience(AUDIENCE)
					.clock(() -> Date.from(clock.instant())).build()
					.parseSignedClaims(token).getPayload();
			return String.valueOf(draftId).equals(claims.getSubject());
		} catch (JwtException | IllegalArgumentException e) {
			return false;
		}
	}

	private static byte[] derive(String secret) {
		try {
			return MessageDigest.getInstance("SHA-256")
					.digest(("clip-draft-video-token:" + secret).getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
