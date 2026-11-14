package com.livestream.livestream.common.security;

import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.comment.Comment;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;

/** DESIGN §4.8 소유자 검증 헬퍼. 서비스 계층에서 명시적으로 부르고, 실패하면 403 FORBIDDEN. */
public final class OwnershipGuard {

	private OwnershipGuard() {
	}

	public static void requireChannelOwner(Channel channel, MemberPrincipal me) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		if (!channel.getMember().getId().equals(me.id())) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
	}

	/** 댓글 수정 · 삭제: 작성자만. 채널 주인도 남의 댓글은 못 고친다. */
	public static void requireAuthor(Comment comment, MemberPrincipal me) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		if (!comment.isWrittenBy(me.id())) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
	}
}
