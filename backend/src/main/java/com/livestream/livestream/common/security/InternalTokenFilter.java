package com.livestream.livestream.common.security;

import com.livestream.livestream.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code /api/internal/**} (MediaMTX 훅) 전용: {@code X-Internal-Token} 이 설정값과 같아야 통과한다 (DESIGN §4.7).
 * <p>인증 콜백 {@code /api/internal/mediamtx/auth} 는 제외한다 — MediaMTX가 헤더를 붙일 수 없고, 대신 Caddy가
 * {@code /api/internal/*} 를 바깥에 노출하지 않는다(§4.9). 설정 토큰이 비어 있으면 모두 거절한다(fail closed).
 */
public class InternalTokenFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Internal-Token";
	static final String INTERNAL_PREFIX = "/api/internal/";
	static final String AUTH_CALLBACK_PATH = "/api/internal/mediamtx/auth";

	private final byte[] expected;

	public InternalTokenFilter(String internalToken) {
		this.expected = internalToken == null ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return !uri.startsWith(INTERNAL_PREFIX) || uri.equals(AUTH_CALLBACK_PATH);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = request.getHeader(HEADER);
		if (expected.length == 0 || token == null
				|| !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
			ErrorResponseWriter.write(response, ErrorCode.UNAUTHENTICATED);
			return;
		}
		chain.doFilter(request, response);
	}
}
