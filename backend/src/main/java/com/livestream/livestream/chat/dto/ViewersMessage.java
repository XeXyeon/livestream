package com.livestream.livestream.chat.dto;

/** 토픽 {@code {type:"viewers", count}}. */
public record ViewersMessage(String type, long count) {

	public static ViewersMessage of(long count) {
		return new ViewersMessage("viewers", count);
	}
}
