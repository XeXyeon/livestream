package com.livestream.livestream.common.security;

import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer} 를 검증해 SecurityContext에 {@link MemberPrincipal} 을 둔다.
 * 토큰이 만료 · 위조여도 여기서 막지 않는다. 공개 경로는 비로그인으로 통과시키고, 보호 경로에서는
 * {@link RestAuthenticationEntryPoint} 가 여기서 남긴 에러 코드(TOKEN_EXPIRED / TOKEN_INVALID)로 401을 내린다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	static final String ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".ERROR_CODE";
	private static final String BEARER = "Bearer ";

	private final JwtProvider jwtProvider;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER)) {
			try {
				MemberPrincipal principal = jwtProvider.parse(header.substring(BEARER.length()).trim());
				SecurityContextHolder.getContext().setAuthentication(
						new UsernamePasswordAuthenticationToken(principal, null, List.of()));
			} catch (ApiException e) {
				request.setAttribute(ERROR_ATTRIBUTE, e.getErrorCode());
			}
		}
		chain.doFilter(request, response);
	}

	static ErrorCode errorOf(HttpServletRequest request) {
		Object attr = request.getAttribute(ERROR_ATTRIBUTE);
		return attr instanceof ErrorCode code ? code : ErrorCode.UNAUTHENTICATED;
	}
}
