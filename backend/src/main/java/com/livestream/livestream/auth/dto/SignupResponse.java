package com.livestream.livestream.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원가입 응답")
public record SignupResponse(@Schema(description = "생성된 회원 id") Long memberId) {
}
