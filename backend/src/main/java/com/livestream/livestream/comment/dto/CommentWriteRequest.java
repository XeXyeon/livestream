package com.livestream.livestream.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "댓글 작성")
public record CommentWriteRequest(
		@Schema(description = "텍스트 1~500자") @NotNull String content,
		@Schema(description = "답글이면 부모 댓글 id(대댓글에는 답글 불가)") Long parentId) {
}
