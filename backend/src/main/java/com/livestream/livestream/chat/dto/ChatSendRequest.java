package com.livestream.livestream.chat.dto;

/** {@code /app/broadcasts/{id}/chat} 전송 본문. content 1~200자. */
public record ChatSendRequest(String content) {
}
