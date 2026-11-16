package com.livestream.livestream.clip.dto;

import com.livestream.livestream.recording.Visibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "라이브 \"최근 N초\" 클립 만들기")
public record LiveClipCreateRequest(
		@Schema(description = "제목(최대 100자). 비우면 방송 제목") @Size(max = 100) String title,
		@Schema(description = "최근 몇 초(5~60). 구간은 요청 시각 기준 [now − N, now)") @NotNull Integer durationSeconds,
		@Schema(description = "PUBLIC · PRIVATE, 생략하면 PUBLIC") Visibility visibility) {
}
