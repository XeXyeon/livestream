package com.livestream.livestream.chat.dto;

import com.livestream.livestream.channel.Category;
import com.livestream.livestream.channel.dto.CategoryResponse;
import java.time.Instant;

/** 토픽 {@code {type:"info", title, category:{code,label}, at}} — 송출 중 바뀐 방송 정보(DESIGN §4.14, FE-5#1). */
public record InfoMessage(String type, String title, CategoryResponse category, Instant at) {

	public static InfoMessage of(String title, Category category, Instant at) {
		return new InfoMessage("info", title, CategoryResponse.of(category), at);
	}
}
