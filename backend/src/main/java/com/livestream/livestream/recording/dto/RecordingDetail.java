package com.livestream.livestream.recording.dto;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.dto.BroadcastSummary;
import com.livestream.livestream.channel.dto.CategoryResponse;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingStatus;
import com.livestream.livestream.recording.Visibility;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** GET /recordings/{id} (DESIGN §4.16): RecordingSummary 필드 + 상태 · 공개 여부 · 재생 URL · 소유 여부. */
@Schema(description = "다시보기 상세")
public record RecordingDetail(
		@Schema(description = "recording id") Long id,
		Long broadcastId,
		String title,
		CategoryResponse category,
		String thumbnailUrl,
		Integer durationSeconds,
		Instant startedAt,
		BroadcastSummary.ChannelRef channel,
		long commentCount,
		@Schema(description = "RECORDING · PROCESSING · READY · FAILED · FILE_DELETED") String status,
		@Schema(description = "PUBLIC · PRIVATE") String visibility,
		@Schema(description = "READY · PUBLIC 일 때만 /vod/<uuid>.mp4. 비공개는 null — 주인이 video-token 으로 재생 URL을 만든다") String videoUrl,
		@Schema(description = "보관 기간이 지나 파일이 지워졌으면 true") boolean fileDeleted,
		@Schema(description = "이 방송에서 승인된 후원 합계(원)") long donationTotal,
		@Schema(description = "로그인 회원이 채널 주인이면 true — 공개 설정 · 비공개 재생") boolean isOwner) {

	public static RecordingDetail of(Recording r, long commentCount, long donationTotal, boolean isOwner) {
		Broadcast b = r.getBroadcast();
		boolean ready = r.getStatus() == RecordingStatus.READY;
		// 공개 폴더(vod/)에 있을 때만. 드물게 file_path 가 private/ 를 가리키면(이동 뒤 롤백) null — /private 는 Caddy 가 SPA 로 넘긴다
		String videoUrl = ready && r.getVisibility() == Visibility.PUBLIC && r.getFilePath() != null
				&& r.getFilePath().startsWith("vod/") ? "/" + r.getFilePath() : null;
		return new RecordingDetail(r.getId(), b.getId(), b.getTitle(), CategoryResponse.of(b.getCategory()),
				BroadcastSummary.thumbnailUrl(b.getThumbnailPath()), r.getDurationSeconds(), b.getStartedAt(),
				new BroadcastSummary.ChannelRef(b.getChannel().getId(), b.getChannel().getName()), commentCount,
				r.getStatus().name(), r.getVisibility().name(), videoUrl, r.getStatus() == RecordingStatus.FILE_DELETED,
				donationTotal, isOwner);
	}
}
