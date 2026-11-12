package com.livestream.livestream.recording;

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
 * 비공개 다시보기 재생 토큰 (DESIGN §4.10 서빙): HS256, sub=recordingId, 5분. {@code <video src>} 는 헤더를 못 붙여 쿼리로 받는다.
 * <p>access 토큰과 서명 키를 분리한다(app.jwt.secret 에서 파생). 같은 키를 쓰면 sub=recordingId 인 재생 토큰이
 * 같은 id 의 회원 access 토큰으로 통과하거나, 반대로 access 토큰이 재생 토큰으로 통과한다. aud 도 확인한다.
 */
@Component
public class VideoTokenService {

	static final Duration TTL = Duration.ofMinutes(5);
	private static final String AUDIENCE = "recording-video";

	private final SecretKey key;
	private final Clock clock;

	@Autowired
	public VideoTokenService(AppProperties props) {
		this(props, Clock.systemUTC());
	}

	VideoTokenService(AppProperties props, Clock clock) {
		this.key = Keys.hmacShaKeyFor(derive(props.jwt().secret()));
		this.clock = clock;
	}

	public String issue(Long recordingId) {
		Instant now = clock.instant();
		return Jwts.builder()
				.subject(String.valueOf(recordingId))
				.audience().add(AUDIENCE).and()
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(TTL)))
				.signWith(key)
				.compact();
	}

	/** 토큰이 이 다시보기용이고 만료 전이면 true. */
	public boolean isValidFor(String token, Long recordingId) {
		if (token == null || token.isBlank()) {
			return false;
		}
		try {
			Claims claims = Jwts.parser().verifyWith(key).requireAudience(AUDIENCE)
					.clock(() -> Date.from(clock.instant())).build()
					.parseSignedClaims(token).getPayload();
			return String.valueOf(recordingId).equals(claims.getSubject());
		} catch (JwtException | IllegalArgumentException e) {
			return false;
		}
	}

	private static byte[] derive(String secret) {
		try {
			return MessageDigest.getInstance("SHA-256")
					.digest(("recording-video-token:" + secret).getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
