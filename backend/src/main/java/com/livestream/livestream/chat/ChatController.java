package com.livestream.livestream.chat;

import com.livestream.livestream.chat.dto.ChatError;
import com.livestream.livestream.chat.dto.ChatSendRequest;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * 채팅 전송 {@code /app/broadcasts/{id}/chat} (DESIGN §4.14). 실패는 연결을 끊지 않고 그 세션의 {@code /user/queue/errors} 로
 * {@code {code, message}} 를 보낸다(익명 전송 · 잘못된 목적지는 {@link StompAuthInterceptor} 가 ERROR 프레임으로 먼저 거절).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatController {

	private final ChatService chatService;

	@MessageMapping("/broadcasts/{broadcastId}/chat")
	public void chat(@DestinationVariable Long broadcastId, @Payload ChatSendRequest request, Principal principal) {
		chatService.send(broadcastId, StompPrincipal.memberOf(principal), request == null ? null : request.content());
	}

	@MessageExceptionHandler(ApiException.class)
	@SendToUser(destinations = StompDestinations.ERRORS_QUEUE, broadcast = false)
	public ChatError onApiException(ApiException e) {
		return new ChatError(e.getErrorCode().name(), e.getMessage());
	}

	@MessageExceptionHandler
	@SendToUser(destinations = StompDestinations.ERRORS_QUEUE, broadcast = false)
	public ChatError onOther(Exception e) {
		log.debug("채팅 처리 실패: {}", e.toString());
		return new ChatError(ErrorCode.VALIDATION_ERROR.name(), ErrorCode.VALIDATION_ERROR.getMessage());
	}
}
