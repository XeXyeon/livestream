package com.livestream.livestream.donation.dto;

import com.livestream.livestream.donation.Donation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "승인된 후원")
public record DonationResponse(
		Long id,
		Long broadcastId,
		Donor donor,
		int amount,
		@Schema(description = "없으면 null") String message,
		Instant approvedAt) {

	public record Donor(String nickname) {
	}

	public static DonationResponse of(Donation d) {
		return new DonationResponse(d.getId(), d.getBroadcast().getId(), new Donor(d.getDonor().getNickname()),
				d.getAmount(), d.getMessage(), d.getApprovedAt());
	}
}
