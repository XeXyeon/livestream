package com.livestream.livestream.chat;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastStatus;
import com.livestream.livestream.chat.dto.ChatMessage;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 채팅 전송 (DESIGN §4.14): 로그인 + 방송 LIVE/DISCONNECTED → 버퍼 추가 → 토픽 발행. */
@Service
@RequiredArgsConstructor
public class ChatService {

	static final int CONTENT_MAX = 200;
	/**
	 * 제어문자(C0 · C1), 줄 · 문단 구분자, 양방향 재정렬 문자(U+202A–202E, U+2066–2069)는 받지 않는다.
	 * 다른 서식 문자(\p{Cf})는 막지 않는다: ZWJ(U+200D)가 이모지 조합에 쓰인다. 대신 보이는 문자가 하나는 있어야 한다.
	 */
	private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[\\p{Cc}\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]");

	private final BroadcastRepository broadcastRepository;
	private final ChatBufferService buffer;
	private final SimpMessagingTemplate messagingTemplate;
	private final TransactionTemplate tx;
	private final ChatRateLimiter rateLimiter;

	public ChatMessage send(Long broadcastId, MemberPrincipal me, String rawContent) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		String content = content(rawContent);
		if (!rateLimiter.tryAcquire(me.id())) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "채팅을 너무 빠르게 보내고 있습니다. 잠시 후 다시 보내 주세요");
		}
		Boolean channelOwner = tx.execute(s -> {
			Broadcast b = broadcastRepository.findActive(broadcastId)
					.orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
			if (b.getStatus() == BroadcastStatus.ENDED) {
				throw new ApiException(ErrorCode.BROADCAST_NOT_LIVE);
			}
			return b.getChannel().getMember().getId().equals(me.id());
		});
		ChatMessage message = ChatMessage.of(UUID.randomUUID().toString(),
				new ChatMessage.Member(me.id(), me.nickname(), Boolean.TRUE.equals(channelOwner)), content, Instant.now());
		buffer.add(broadcastId, message);
		messagingTemplate.convertAndSend(StompDestinations.broadcastTopic(broadcastId), message);
		return message;
	}

	static String content(String raw) {
		String value = raw == null ? "" : raw.strip();
		if (value.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "메시지를 입력해 주세요");
		}
		if (FORBIDDEN_CHARS.matcher(value).find()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "메시지에 사용할 수 없는 문자가 있습니다");
		}
		if (value.codePoints().noneMatch(ChatService::isVisible)) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "메시지를 입력해 주세요");
		}
		if (value.codePointCount(0, value.length()) > CONTENT_MAX) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "메시지는 " + CONTENT_MAX + "자 이하여야 합니다");
		}
		return value;
	}

	private static boolean isVisible(int cp) {
		int type = Character.getType(cp);
		return !Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && type != Character.FORMAT
				&& type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK;
	}
}
