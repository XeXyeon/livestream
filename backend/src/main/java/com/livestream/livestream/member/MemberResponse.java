package com.livestream.livestream.member;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "내 정보")
public record MemberResponse(
		@Schema(description = "회원 id") Long id,
		@Schema(description = "이메일") String email,
		@Schema(description = "닉네임") String nickname,
		@Schema(description = "내 채널 id") Long channelId,
		@Schema(description = "가입 시각(UTC ISO-8601)") Instant createdAt) {
}
