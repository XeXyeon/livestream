package com.livestream.livestream.channel.dto;

import com.livestream.livestream.channel.Category;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "카테고리 (code로 분기, label은 화면 문구)")
public record CategoryResponse(
		@Schema(example = "GAME") String code,
		@Schema(example = "게임") String label) {

	public static CategoryResponse of(Category category) {
		return new CategoryResponse(category.name(), category.getLabel());
	}
}
