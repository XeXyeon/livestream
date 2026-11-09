package com.livestream.livestream.common.config;

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
 * <p>/api/internal/** 은 InternalTokenFilter(BC-1)가 생기기 전까지 열지 않는다(기본 인증 필요 → 401).
 */
@Configuration
public class SecurityConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider,
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
				.addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
