package com.livestream.livestream.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인한 회원 요약")
public record MemberSummary(
		@Schema(description = "회원 id") Long id,
		@Schema(description = "닉네임") String nickname,
		@Schema(description = "내 채널 id") Long channelId) {
}
