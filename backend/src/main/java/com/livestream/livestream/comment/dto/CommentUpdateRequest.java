package com.livestream.livestream.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "댓글 수정")
public record CommentUpdateRequest(@Schema(description = "텍스트 1~500자") @NotNull String content) {
}
