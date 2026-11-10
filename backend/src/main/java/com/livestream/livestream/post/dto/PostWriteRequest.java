package com.livestream.livestream.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "게시글 작성")
public record PostWriteRequest(
		@Schema(description = "제목 1~100자, 줄바꿈 불가") @NotNull String title,
		@Schema(description = "본문 텍스트 1~5,000자") @NotNull String content) {
}
