package com.livestream.livestream.donation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "결제위젯 성공 콜백 값 그대로")
public record DonationConfirmRequest(
		@NotBlank @Size(max = 200) String paymentKey,
		@NotBlank @Size(max = 64) String orderId,
		@NotNull Long amount) {
}
