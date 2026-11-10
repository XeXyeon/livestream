package com.livestream.livestream.chat.dto;

/** {@code /user/queue/errors} 로 보내는 채팅 전송 실패 {@code {code, message}}. */
public record ChatError(String code, String message) {
}
