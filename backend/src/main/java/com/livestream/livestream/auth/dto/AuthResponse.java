package com.livestream.livestream.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 · 갱신 응답. refresh 토큰은 본문이 아니라 HttpOnly 쿠키로 내려간다")
public record AuthResponse(
		@Schema(description = "Access JWT. 메모리에만 보관하고 Authorization: Bearer 로 보낸다") String accessToken,
		MemberSummary member) {
}
