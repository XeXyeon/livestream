package com.livestream.livestream.auth;

import com.livestream.livestream.auth.dto.AuthResponse;

/** 서비스가 컨트롤러에 돌려주는 결과: 응답 본문 + 쿠키에 실을 refresh 토큰 원문. */
public record AuthResult(AuthResponse response, String refreshToken) {
}
