package com.livestream.livestream.search.dto;

import com.livestream.livestream.channel.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/** 채널 카드 (DESIGN §4.16 ChannelSummary). */
@Schema(description = "채널 요약(검색 결과 카드)")
public record ChannelSummary(
		Long id,
		String name,
		@Schema(description = "채널 소개, 없으면 null") String description,
		@Schema(description = "채널의 다음 방송 카테고리") CategoryResponse category,
		@Schema(description = "진행 중(LIVE · DISCONNECTED) 방송이 있는지") boolean isLive,
		@Schema(description = "진행 중 방송 id, 없으면 null") Long liveBroadcastId,
		@Schema(description = "진행 중 방송의 시청자 수, 없으면 0") long viewerCount,
		long followerCount) {
}
