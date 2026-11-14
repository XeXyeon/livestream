package com.livestream.livestream.recording.dto;

import com.livestream.livestream.recording.Visibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "다시보기 공개 설정")
public record VisibilityUpdateRequest(@Schema(description = "PUBLIC · PRIVATE") @NotNull Visibility visibility) {
}
