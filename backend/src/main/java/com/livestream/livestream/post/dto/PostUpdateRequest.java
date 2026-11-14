package com.livestream.livestream.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "게시글 수정 — 보낸 필드만 반영")
public record PostUpdateRequest(
		@Schema(description = "제목 1~100자, 줄바꿈 불가") String title,
		@Schema(description = "본문 텍스트 1~5,000자") String content) {
}
