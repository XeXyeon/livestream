package com.livestream.livestream.donation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "결제위젯에 넘길 주문 정보")
public record DonationCreateResponse(
		Long donationId,
		@Schema(description = "결제위젯 orderId(UUID)") String orderId,
		int amount,
		@Schema(description = "Toss 클라이언트 키(테스트)") String clientKey,
		String orderName,
		String customerName) {
}
