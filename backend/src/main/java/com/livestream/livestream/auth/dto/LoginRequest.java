package com.livestream.livestream.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "로그인 요청")
public record LoginRequest(
		@Schema(example = "streamer@example.com") @NotBlank(message = "이메일을 입력해 주세요") @Size(max = 255, message = "이메일은 255자 이하여야 합니다") String email,
		@Schema(example = "password123") @NotBlank(message = "비밀번호를 입력해 주세요") @Size(max = 128, message = "비밀번호가 너무 깁니다") String password) {
}
