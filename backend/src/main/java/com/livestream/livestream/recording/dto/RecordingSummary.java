package com.livestream.livestream.recording.dto;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.dto.BroadcastSummary;
import com.livestream.livestream.channel.dto.CategoryResponse;
import com.livestream.livestream.recording.Recording;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 다시보기 카드 (DESIGN §4.16 RecordingSummary). REC-2 의 GET /recordings 도 이 모양을 쓴다. */
@Schema(description = "다시보기 요약")
public record RecordingSummary(
		@Schema(description = "recording id") Long id,
		Long broadcastId,
		String title,
		CategoryResponse category,
		String thumbnailUrl,
		Integer durationSeconds,
		Instant startedAt,
		BroadcastSummary.ChannelRef channel,
		long commentCount) {

	public static RecordingSummary of(Recording r, long commentCount) {
		Broadcast b = r.getBroadcast();
		return new RecordingSummary(r.getId(), b.getId(), b.getTitle(), CategoryResponse.of(b.getCategory()),
				BroadcastSummary.thumbnailUrl(b.getThumbnailPath()), r.getDurationSeconds(), b.getStartedAt(),
				new BroadcastSummary.ChannelRef(b.getChannel().getId(), b.getChannel().getName()), commentCount);
	}
}
