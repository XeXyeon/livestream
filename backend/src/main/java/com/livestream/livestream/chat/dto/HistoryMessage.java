package com.livestream.livestream.chat.dto;

import java.util.List;

/** 구독 직후 그 세션에만: {@code {type:"history", messages:[chat|donation …]}} (오래된 것부터). */
public record HistoryMessage(String type, List<Object> messages) {

	public static HistoryMessage of(List<Object> messages) {
		return new HistoryMessage("history", messages);
	}
}
