package com.livestream.livestream.common.security;

import com.livestream.livestream.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 보호 경로에 토큰 없이 오면 401 UNAUTHENTICATED.
 * 만료 · 위조 토큰은 {@link JwtAuthenticationFilter} 가 먼저 TOKEN_EXPIRED / TOKEN_INVALID 로 응답하므로 여기 오지 않는다.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		ErrorResponseWriter.write(response, ErrorCode.UNAUTHENTICATED);
	}
}
