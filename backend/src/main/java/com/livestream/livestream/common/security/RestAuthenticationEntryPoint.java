package com.livestream.livestream.common.security;

import com.livestream.livestream.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** 보호 경로에 인증 없이 오면 401 {code, message}. 만료 토큰이면 TOKEN_EXPIRED로 프론트가 refresh를 시도하게 한다. */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		ErrorResponseWriter.write(response, JwtAuthenticationFilter.errorOf(request));
	}
}
