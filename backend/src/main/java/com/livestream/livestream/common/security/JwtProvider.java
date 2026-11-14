package com.livestream.livestream.common.security;

import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/** Access JWT 발급 · 검증 (DESIGN §4.7): sub=memberId, nickname, channelId, HS256. */
@Component
public class JwtProvider {

	private static final String CLAIM_NICKNAME = "nickname";
	private static final String CLAIM_CHANNEL_ID = "channelId";

	private final SecretKey key;
	private final Duration accessTtl;
	private final Clock clock;

	public JwtProvider(AppProperties props) {
		this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
		this.accessTtl = Duration.ofMinutes(props.jwt().accessMinutes());
		this.clock = Clock.systemUTC();
	}

	public String createAccessToken(MemberPrincipal principal) {
		Instant now = clock.instant();
		return Jwts.builder()
				.subject(String.valueOf(principal.id()))
				.claim(CLAIM_NICKNAME, principal.nickname())
				.claim(CLAIM_CHANNEL_ID, principal.channelId())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(accessTtl)))
				.signWith(key)
				.compact();
	}

	/** @throws ApiException TOKEN_EXPIRED(만료) 또는 TOKEN_INVALID(그 외) */
	public MemberPrincipal parse(String token) {
		try {
			Claims claims = Jwts.parser().verifyWith(key).clock(() -> Date.from(clock.instant())).build()
					.parseSignedClaims(token).getPayload();
			// JSON 숫자는 작으면 Integer로 읽히므로 Number로 받아 Long으로 바꾼다.
			Number channelId = claims.get(CLAIM_CHANNEL_ID, Number.class);
			return new MemberPrincipal(Long.valueOf(claims.getSubject()), claims.get(CLAIM_NICKNAME, String.class),
					channelId == null ? null : channelId.longValue());
		} catch (ExpiredJwtException e) {
			throw new ApiException(ErrorCode.TOKEN_EXPIRED);
		} catch (JwtException | IllegalArgumentException e) {
			throw new ApiException(ErrorCode.TOKEN_INVALID);
		}
	}
}
