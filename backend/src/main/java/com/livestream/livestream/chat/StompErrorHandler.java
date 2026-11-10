package com.livestream.livestream.chat;

import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * 인바운드 처리 실패를 ERROR 프레임으로: {@code message} 헤더 = 에러 코드(TOKEN_EXPIRED 등), 본문 {@code {code, message}}.
 * 프론트는 CONNECT 거절이 TOKEN_EXPIRED 면 refresh 후 재연결한다(§6.6).
 */
@Slf4j
public class StompErrorHandler extends StompSubProtocolErrorHandler {

	@Override
	public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
		ApiException api = findApiException(ex);
		ErrorCode code;
		String text;
		if (api != null) {
			code = api.getErrorCode();
			text = api.getMessage();
		} else {
			// 기본 구현은 예외 메시지(채널 이름 등 내부 정보)를 그대로 내보내므로 고정 문구로 바꾸고 원인은 로그에만 남긴다.
			log.warn("STOMP 인바운드 처리 실패: {}", ex.toString());
			code = ErrorCode.INTERNAL_ERROR;
			text = code.getMessage();
		}
		StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
		accessor.setMessage(code.name());
		accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
		if (clientMessage != null) {
			StompHeaderAccessor client = StompHeaderAccessor.wrap(clientMessage);
			if (client.getReceipt() != null) {
				accessor.setReceiptId(client.getReceipt());
			}
		}
		accessor.setLeaveMutable(true);
		byte[] body = ("{\"code\":\"" + code.name() + "\",\"message\":\"" + escape(text) + "\"}")
				.getBytes(StandardCharsets.UTF_8);
		return MessageBuilder.createMessage(body, accessor.getMessageHeaders());
	}

	private static ApiException findApiException(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof ApiException api) {
				return api;
			}
		}
		return null;
	}

	private static String escape(String s) {
		return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
