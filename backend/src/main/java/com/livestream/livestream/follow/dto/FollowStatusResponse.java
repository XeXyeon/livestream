package com.livestream.livestream.follow.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** POST · DELETE /channels/{id}/follow — 팔로우 상태. */
@Schema(description = "채널 팔로우 상태")
public record FollowStatusResponse(
		Long channelId,
		@Schema(description = "요청 뒤 내가 이 채널을 팔로우 중인지") boolean following,
		@Schema(description = "채널의 팔로워 수") long followerCount) {
}
