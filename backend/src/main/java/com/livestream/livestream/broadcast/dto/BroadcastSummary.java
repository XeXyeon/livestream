package com.livestream.livestream.broadcast.dto;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.channel.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 방송 카드 (DESIGN §4.16 BroadcastSummary). */
@Schema(description = "방송 요약(목록 · 추천 카드)")
public record BroadcastSummary(
		Long id,
		String title,
		CategoryResponse category,
		@Schema(description = "\"/uploads/thumbnails/<id>.jpg\", 없으면 null(프론트 기본 이미지)") String thumbnailUrl,
		@Schema(description = "LIVE | DISCONNECTED | ENDED") String status,
		Instant startedAt,
		long viewerCount,
		ChannelRef channel) {

	public record ChannelRef(Long id, String name) {
	}

	public static BroadcastSummary of(Broadcast b, long viewerCount) {
		return new BroadcastSummary(b.getId(), b.getTitle(), CategoryResponse.of(b.getCategory()),
				thumbnailUrl(b.getThumbnailPath()), b.getStatus().name(), b.getStartedAt(), viewerCount,
				new ChannelRef(b.getChannel().getId(), b.getChannel().getName()));
	}

	/** 썸네일 상대 경로(uploads 기준) → Caddy 정적 경로. */
	public static String thumbnailUrl(String thumbnailPath) {
		return thumbnailPath == null ? null : "/uploads/" + thumbnailPath;
	}
}
