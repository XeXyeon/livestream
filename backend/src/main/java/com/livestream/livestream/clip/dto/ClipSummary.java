package com.livestream.livestream.clip.dto;

import com.livestream.livestream.broadcast.dto.BroadcastSummary;
import com.livestream.livestream.clip.Clip;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "클립 목록 항목")
public record ClipSummary(
		Long id,
		String title,
		@Schema(description = "길이(초). READY 면 실제 길이, 아니면 요청 길이") int durationSeconds,
		@Schema(description = "\"/uploads/thumbnails/clips/<id>.jpg\", 없으면 null") String thumbnailUrl,
		@Schema(description = "PROCESSING | READY | FAILED") String status,
		@Schema(description = "PUBLIC | PRIVATE") String visibility,
		Instant createdAt,
		CreatorRef creator,
		ChannelRef channel,
		Long broadcastId,
		Long recordingId) {

	@Schema(description = "만든 회원")
	public record CreatorRef(String nickname) {
	}

	@Schema(description = "클립이 속한 채널")
	public record ChannelRef(Long id, String name) {
	}

	/** channel · creator 를 함께 읽은 클립에서 만든다(broadcast · recording 은 id 만 쓴다). */
	public static ClipSummary of(Clip c) {
		return new ClipSummary(c.getId(), c.getTitle(), c.getDurationSeconds(),
				BroadcastSummary.thumbnailUrl(c.getThumbnailPath()), c.getStatus().name(), c.getVisibility().name(),
				c.getCreatedAt(), new CreatorRef(c.getCreator().getNickname()),
				new ChannelRef(c.getChannel().getId(), c.getChannel().getName()),
				c.getBroadcast().getId(), c.getRecording().getId());
	}
}
