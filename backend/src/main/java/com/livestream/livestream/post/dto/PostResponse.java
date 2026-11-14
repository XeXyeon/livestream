package com.livestream.livestream.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "게시글 상세")
public record PostResponse(
		Long id,
		ChannelRef channel,
		String title,
		String content,
		@Schema(description = "댓글 수(삭제 표시 댓글 제외)") long commentCount,
		Instant createdAt,
		Instant updatedAt,
		@Schema(description = "로그인 회원이 채널 주인이면 true — 수정 · 삭제 버튼 표시용") boolean isOwner) {

	public record ChannelRef(Long id, String name, @Schema(description = "채널 주인(작성자) 닉네임") String ownerNickname) {
	}
}
