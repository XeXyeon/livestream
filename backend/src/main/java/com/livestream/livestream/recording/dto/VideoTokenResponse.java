package com.livestream.livestream.recording.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "비공개 다시보기 재생 토큰(5분)")
public record VideoTokenResponse(
		@Schema(description = "재생 토큰") String token,
		@Schema(description = "<video src> 에 넣을 URL: /api/recordings/{id}/video?token=…") String videoUrl) {
}
