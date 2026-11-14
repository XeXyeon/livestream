package com.livestream.livestream.donation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "내 채널이 받은 후원(승인만), 방송별")
public record ReceivedDonationsResponse(long totalAmount, List<ReceivedDonationByBroadcast> byBroadcast) {
}
