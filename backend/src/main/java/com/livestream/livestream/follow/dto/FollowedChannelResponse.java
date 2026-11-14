package com.livestream.livestream.follow.dto;

import com.livestream.livestream.channel.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** GET /members/me/follows 의 한 항목. */
@Schema(description = "내가 팔로우한 채널")
public record FollowedChannelResponse(
		Long channelId,
		String name,
		CategoryResponse category,
		@Schema(description = "진행 중(LIVE · DISCONNECTED) 방송이 있는지") boolean isLive,
		@Schema(description = "진행 중 방송 id, 없으면 null") Long liveBroadcastId,
		@Schema(description = "진행 중 방송의 시청자 수, 없으면 0") long viewerCount,
		@Schema(description = "팔로우한 시각(UTC)") Instant followedAt) {
}
