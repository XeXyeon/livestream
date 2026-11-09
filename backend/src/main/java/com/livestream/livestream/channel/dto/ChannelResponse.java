package com.livestream.livestream.channel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** GET /channels/{id} — 누구나. 키 · 송출 정보는 담지 않는다. */
@Schema(description = "채널 공개 정보")
public record ChannelResponse(
		Long id,
		String name,
		String description,
		CategoryResponse category,
		@Schema(description = "진행 중(LIVE · DISCONNECTED) 방송 id, 없으면 null") Long liveBroadcastId,
		@Schema(description = "공개 다시보기 수") long recordingCount,
		@Schema(description = "커뮤니티 글 수") long postCount,
		Owner owner) {

	public record Owner(String nickname) {
	}
}
