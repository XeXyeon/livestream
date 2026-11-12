package com.livestream.livestream.chat.dto;

import java.time.Instant;

/** 토픽 {@code {type:"chat", id, member:{id, nickname, isChannelOwner}, content, sentAt}} (DESIGN §4.14). */
public record ChatMessage(String type, String id, Member member, String content, Instant sentAt) {

	public static final String TYPE = "chat";

	public static ChatMessage of(String id, Member member, String content, Instant sentAt) {
		return new ChatMessage(TYPE, id, member, content, sentAt);
	}

	public record Member(Long id, String nickname, boolean isChannelOwner) {
	}
}
