package com.livestream.livestream.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "회원가입 요청")
public record SignupRequest(
		@Schema(description = "이메일(소문자로 정규화되어 저장)", example = "streamer@example.com")
		@NotBlank(message = "이메일을 입력해 주세요")
		@Email(message = "이메일 형식이 올바르지 않습니다")
		@Size(max = 255, message = "이메일은 255자 이하여야 합니다")
		String email,

		@Schema(description = "비밀번호 8~64자", example = "password123")
		@NotBlank(message = "비밀번호를 입력해 주세요")
		@Size(min = 8, max = 64, message = "비밀번호는 8~64자여야 합니다")
		String password,

		@Schema(description = "닉네임 2~20자. 채널 이름의 기본값", example = "스트리머")
		@NotBlank(message = "닉네임을 입력해 주세요")
		@Size(min = 2, max = 20, message = "닉네임은 2~20자여야 합니다")
		String nickname) {
}
