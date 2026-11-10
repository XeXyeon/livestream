package com.livestream.livestream.broadcast.dto;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.channel.dto.CategoryResponse;
import com.livestream.livestream.recording.Recording;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** GET /members/me/broadcasts — 내 방송 기록(삭제 제외) + 다시보기 상태 · 공개 여부. */
@Schema(description = "내 방송 기록")
public record MyBroadcastItem(
		Long id,
		String title,
		CategoryResponse category,
		String thumbnailUrl,
		String status,
		Instant startedAt,
		Instant endedAt,
		@Schema(description = "다시보기, 없으면 null") RecordingRef recording) {

	public static MyBroadcastItem of(Broadcast b, Recording r) {
		return new MyBroadcastItem(b.getId(), b.getTitle(), CategoryResponse.of(b.getCategory()),
				BroadcastSummary.thumbnailUrl(b.getThumbnailPath()), b.getStatus().name(), b.getStartedAt(),
				b.getEndedAt(), RecordingRef.of(r));
	}
}
