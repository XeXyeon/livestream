package com.livestream.livestream.clip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "클립 상세. 프론트는 PROCESSING 이면 2초마다 다시 부른다")
public record ClipDetail(
		Long id,
		String title,
		@Schema(description = "PROCESSING | READY | FAILED") String status,
		@Schema(description = "PUBLIC | PRIVATE") String visibility,
		@Schema(description = "RECORDING | LIVE") String source,
		@Schema(description = "방송 시작(= 다시보기 0초) 기준 시작 위치(초)") int startSeconds,
		@Schema(description = "길이(초). READY 면 실제 길이") int durationSeconds,
		@Schema(description = "\"/uploads/thumbnails/clips/<id>.jpg\", 없으면 null") String thumbnailUrl,
		Instant createdAt,
		ClipSummary.CreatorRef creator,
		ClipSummary.ChannelRef channel,
		Long broadcastId,
		Long recordingId,
		@Schema(description = "원본 다시보기로 갈 수 있는지: 원본이 READY · PUBLIC 이거나 내가 채널 주인") boolean recordingAvailable,
		@Schema(description = "READY · PUBLIC 일 때 \"/vod/clips/<uuid>.mp4\", 아니면 null(비공개는 video-token)") String videoUrl,
		@Schema(description = "FAILED 일 때 사유. 만든 회원 · 채널 주인에게만, 그 밖에는 null") String failureReason,
		@Schema(description = "내가 채널 주인인지") boolean isOwner,
		@Schema(description = "내가 만든 클립인지") boolean isCreator) {
}
