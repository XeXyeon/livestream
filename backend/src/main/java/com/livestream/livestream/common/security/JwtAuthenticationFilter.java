package com.livestream.livestream.common.security;

import com.livestream.livestream.common.error.ApiException;
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
 * {@code Authorization: Bearer} 를 검증해 SecurityContext에 {@link MemberPrincipal} 을 둔다 (DESIGN §4.7).
 * <ul>
 *   <li>Bearer 헤더가 없으면 익명으로 통과한다(공개 경로는 그대로, 보호 경로는 EntryPoint가 401 UNAUTHENTICATED).</li>
 *   <li>Bearer 헤더가 <b>있는데</b> 만료 · 위조면 공개 경로라도 여기서 바로 401 TOKEN_EXPIRED / TOKEN_INVALID 를 쓴다.
 *       프론트는 로그인 상태일 때만 헤더를 붙이므로, 조용히 익명 처리하면 isOwner=false 같은 잘못된 응답을 refresh 없이 쓰게 된다.</li>
 *   <li>{@code /api/auth/**} 는 검사하지 않는다. access 토큰을 쓰지 않는 쿠키 기반 경로인데, 프론트의 로그아웃은
 *       {@code apiFetch} 로 만료 access 를 붙여 보내고 {@code /auth/} 경로는 refresh 재시도를 하지 않으므로, 여기서 401을 주면
 *       refresh 쿠키가 폐기되지 않은 채 로그아웃이 실패한다(만료 access 가 남은 채 로그인할 때도 같다).
 *       그러므로 {@code /api/auth/} 아래에는 access 가 필요한 엔드포인트를 두지 않는다.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER = "Bearer ";
	private static final String AUTH_PATH_PREFIX = "/api/auth/";

	private final JwtProvider jwtProvider;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith(AUTH_PATH_PREFIX);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.startsWith(BEARER)) {
			MemberPrincipal principal;
			try {
				principal = jwtProvider.parse(header.substring(BEARER.length()).trim());
			} catch (ApiException e) {
				ErrorResponseWriter.write(response, e.getErrorCode());
				return;
			}
			SecurityContextHolder.getContext().setAuthentication(
					new UsernamePasswordAuthenticationToken(principal, null, List.of()));
		}
		chain.doFilter(request, response);
	}
}
