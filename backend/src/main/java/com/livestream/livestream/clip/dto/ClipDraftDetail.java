package com.livestream.livestream.clip.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "라이브 클립 초안(최근 2분). 만든 회원에게만 보인다. 프론트는 PENDING 이면 2초마다 다시 부른다")
public record ClipDraftDetail(
		Long id,
		Long broadcastId,
		@Schema(description = "PENDING | READY | FAILED") String status,
		@Schema(description = "방송 시작 기준 창의 시작 위치(초)") int windowStartSeconds,
		@Schema(description = "창 길이(초). READY 면 받은 파일의 실제 길이(내림). 클립 구간은 start + duration ≤ 이 값") int windowSeconds,
		@Schema(description = "READY 일 때 \"/api/clip-drafts/{id}/video?token=…\"(10분 유효), 아니면 null") String videoUrl,
		Instant createdAt) {
}
