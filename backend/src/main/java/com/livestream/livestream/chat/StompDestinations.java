package com.livestream.livestream.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** STOMP 목적지 이름 (DESIGN §4.14). */
public final class StompDestinations {

	public static final String ERRORS_QUEUE = "/queue/errors";
	public static final String USER_ERRORS = "/user" + ERRORS_QUEUE;
	private static final Pattern BROADCAST_TOPIC = Pattern.compile("/topic/broadcasts/([1-9]\\d{0,17})");

	private StompDestinations() {
	}

	public static String broadcastTopic(Long broadcastId) {
		return "/topic/broadcasts/" + broadcastId;
	}

	/** {@code /topic/broadcasts/{id}} 이면 id, 아니면 null. 앞자리 0은 받지 않는다(브로커 목적지와 집계 대상이 어긋나지 않게). */
	public static Long broadcastIdOf(String destination) {
		if (destination == null) {
			return null;
		}
		Matcher m = BROADCAST_TOPIC.matcher(destination);
		return m.matches() ? Long.valueOf(m.group(1)) : null;
	}
}
