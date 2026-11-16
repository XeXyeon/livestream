package com.livestream.livestream.clip.dto;

import com.livestream.livestream.recording.Visibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "클립 제목 · 공개 설정 수정(만든 회원 또는 채널 주인). 둘 다 선택")
public record ClipUpdateRequest(
		@Schema(description = "제목(최대 100자). 공백만이면 방송 제목") @Size(max = 100) String title,
		@Schema(description = "PUBLIC · PRIVATE. READY 면 영상 파일을 vod/clips/ ↔ private/clips/ 로 옮긴다") Visibility visibility) {
}
