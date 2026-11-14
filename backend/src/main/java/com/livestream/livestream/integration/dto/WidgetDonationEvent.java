package com.livestream.livestream.integration.dto;

import java.time.Instant;

/**
 * SSE {@code event: donation} 의 data (DESIGN §4.15). 외부 연동용 공개 형식이라 {@code version} 을 둔다.
 * {@code createdAt} 은 후원이 승인된 시각이다.
 */
public record WidgetDonationEvent(int version, Long id, String type, Long broadcastId, Donor donor, int amount, String message,
		Instant createdAt) {

	public static final int VERSION = 1;

	public static WidgetDonationEvent of(Long id, Long broadcastId, String donorNickname, int amount, String message,
			Instant approvedAt) {
		return new WidgetDonationEvent(VERSION, id, "donation", broadcastId, new Donor(donorNickname), amount, message, approvedAt);
	}

	public record Donor(String nickname) {
	}
}
