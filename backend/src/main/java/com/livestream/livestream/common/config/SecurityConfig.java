package com.livestream.livestream.common.config;

import com.livestream.livestream.common.security.InternalTokenFilter;
import com.livestream.livestream.common.security.JwtAuthenticationFilter;
import com.livestream.livestream.common.security.JwtProvider;
import com.livestream.livestream.common.security.RestAccessDeniedHandler;
import com.livestream.livestream.common.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * DESIGN §4.7 · §4.8. 세션 STATELESS, CSRF 비활성(refresh 쿠키는 /api/auth 전용 + SameSite=Strict).
 * 공개 경로는 §4.8의 "누구나" 행, 그 외 /api/** 는 인증 필요. 소유자 · 작성자 검증은 서비스 계층이 한다.
 * <p>/api/internal/** 은 JWT 인증 대신 {@link InternalTokenFilter}(X-Internal-Token)로만 지킨다. 인증 콜백은 그 필터에서도
 * 빠지며, Caddy가 바깥에 노출하지 않는 것으로 막는다(§4.9).
 */
@Configuration
public class SecurityConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider, AppProperties props,
			RestAuthenticationEntryPoint entryPoint, RestAccessDeniedHandler accessDeniedHandler) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.cors(AbstractHttpConfigurer::disable)
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/error", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
						.requestMatchers("/ws/**").permitAll()
						// MediaMTX 인증 콜백 · 훅. 토큰 검사는 InternalTokenFilter가 한다.
						.requestMatchers("/api/internal/**").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login",
								"/api/auth/refresh", "/api/auth/logout").permitAll()
						// 내 채널은 로그인 필요 — 아래 공개 채널 패턴보다 먼저 선언한다.
						.requestMatchers("/api/channels/me", "/api/channels/me/**").authenticated()
						.requestMatchers(HttpMethod.GET,
								"/api/channels/*", "/api/channels/*/broadcasts", "/api/channels/*/posts",
								"/api/broadcasts", "/api/broadcasts/*", "/api/broadcasts/*/recommendations",
								"/api/recordings", "/api/recordings/*", "/api/recordings/*/comments",
								"/api/posts/*", "/api/posts/*/comments",
								"/api/public/widget/*/events").permitAll()
						.requestMatchers("/api/**").authenticated()
						.anyRequest().permitAll())
				.addFilterBefore(new InternalTokenFilter(props.mediamtx().internalToken()),
						UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
