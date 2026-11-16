package com.livestream.livestream.clip.dto;

import com.livestream.livestream.recording.Visibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "다시보기 · 라이브 클립 초안에서 클립 만들기")
public record ClipCreateRequest(
		@Schema(description = "제목(최대 100자). 비우면 방송 제목") @Size(max = 100) String title,
		@Schema(description = "시작 위치(초, 0 이상). 다시보기는 영상 기준, 초안은 초안 기준(서버가 방송 기준으로 바꿔 저장)") @NotNull Integer startSeconds,
		@Schema(description = "길이(초, app.clip.min-seconds ~ max-seconds = 5~60)") @NotNull Integer durationSeconds,
		@Schema(description = "PUBLIC · PRIVATE, 생략하면 PUBLIC") Visibility visibility) {
}
