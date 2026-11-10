package com.livestream.livestream.chat.dto;

import java.time.Instant;

/** 토픽 {@code {type:"donation", id, donor:{nickname}, amount, message, approvedAt}} (DESIGN §4.14). id 는 후원 id. */
public record DonationMessage(String type, Long id, Donor donor, int amount, String message, Instant approvedAt) {

	public static final String TYPE = "donation";

	public static DonationMessage of(Long id, String donorNickname, int amount, String message, Instant approvedAt) {
		return new DonationMessage(TYPE, id, new Donor(donorNickname), amount, message, approvedAt);
	}

	public record Donor(String nickname) {
	}
}
