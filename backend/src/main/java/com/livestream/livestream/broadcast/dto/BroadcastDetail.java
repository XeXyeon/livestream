package com.livestream.livestream.broadcast.dto;

import com.livestream.livestream.channel.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** GET /broadcasts/{id} (DESIGN §4.16 BroadcastDetail = Summary + 상세). */
@Schema(description = "방송 상세")
public record BroadcastDetail(
		Long id,
		String title,
		CategoryResponse category,
		String thumbnailUrl,
		@Schema(description = "LIVE | DISCONNECTED | ENDED") String status,
		Instant startedAt,
		long viewerCount,
		BroadcastSummary.ChannelRef channel,
		Instant endedAt,
		@Schema(description = "BROWSER_PC | BROWSER_MOBILE | STREAM_KEY") String source,
		@Schema(description = "whep | grid — viewerCount >= app.grid.threshold 이면 grid") String recommendedMode,
		Play play,
		@Schema(description = "승인된 후원 합계(원)") long donationTotal,
		@Schema(description = "다시보기, 없으면 null") RecordingRef recording,
		boolean isOwner) {

	public record Play(
			@Schema(example = "/live/3/whep") String whepUrl,
			@Schema(example = "/live/3/index.m3u8") String hlsUrl) {
	}
}
