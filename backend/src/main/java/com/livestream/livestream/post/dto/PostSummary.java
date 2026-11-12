package com.livestream.livestream.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "게시글 목록 항목")
public record PostSummary(
		Long id,
		String title,
		@Schema(description = "본문 앞부분(최대 100자)") String preview,
		@Schema(description = "댓글 수(삭제 표시 댓글 제외)") long commentCount,
		Instant createdAt,
		Instant updatedAt) {
}
