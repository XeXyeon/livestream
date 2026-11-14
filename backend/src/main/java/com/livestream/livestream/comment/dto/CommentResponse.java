package com.livestream.livestream.comment.dto;

import com.livestream.livestream.comment.Comment;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** DESIGN §4.12: 평면 목록(createdAt 오름차순)의 한 항목. 트리는 프론트가 parentId 로 만든다. */
@Schema(description = "댓글")
public record CommentResponse(
		Long id,
		@Schema(description = "대댓글이면 부모 댓글 id, 아니면 null") Long parentId,
		@Schema(description = "삭제된 댓글이면 null(\"삭제된 댓글입니다\" 표시)") String content,
		boolean deleted,
		Author author,
		Instant createdAt,
		Instant updatedAt) {

	public record Author(Long id, String nickname,
			@Schema(description = "작성자가 이 다시보기 · 게시글의 채널 주인이면 true") boolean isChannelOwner) {
	}

	public static CommentResponse of(Comment c, Long channelOwnerId) {
		Long authorId = c.getMember().getId();
		return new CommentResponse(c.getId(), c.getParent() == null ? null : c.getParent().getId(),
				c.isDeleted() ? null : c.getContent(), c.isDeleted(),
				new Author(authorId, c.getMember().getNickname(), authorId.equals(channelOwnerId)),
				c.getCreatedAt(), c.getUpdatedAt());
	}
}
