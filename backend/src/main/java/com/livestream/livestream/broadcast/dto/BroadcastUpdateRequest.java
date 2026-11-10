package com.livestream.livestream.broadcast.dto;

import com.livestream.livestream.channel.Category;
import io.swagger.v3.oas.annotations.media.Schema;

/** PATCH /broadcasts/{id}. null 은 변경 없음. ENDED 방송은 title 만(category 가 오면 409). */
@Schema(description = "방송 정보 수정")
public record BroadcastUpdateRequest(
		@Schema(description = "1~100자") String title,
		@Schema(description = "LIVE · DISCONNECTED 일 때만") Category category) {
}
