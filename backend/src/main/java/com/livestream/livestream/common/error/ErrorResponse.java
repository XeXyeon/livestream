package com.livestream.livestream.common.error;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "에러 응답")
public record ErrorResponse(
		@Schema(description = "분기용 에러 코드", example = "LOGIN_FAILED") String code,
		@Schema(description = "화면에 보여 줄 문구", example = "이메일 또는 비밀번호가 올바르지 않습니다") String message) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.getMessage());
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.name(), message);
	}
}
