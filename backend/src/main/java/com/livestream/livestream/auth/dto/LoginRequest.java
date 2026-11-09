package com.livestream.livestream.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "로그인 요청")
public record LoginRequest(
		@Schema(example = "streamer@example.com") @NotBlank(message = "이메일을 입력해 주세요") String email,
		@Schema(example = "password123") @NotBlank(message = "비밀번호를 입력해 주세요") String password) {
}
