package com.livestream.livestream.donation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "후원 생성")
public record DonationCreateRequest(
		@Schema(description = "금액(원), 1000~100000") @NotNull Integer amount,
		@Schema(description = "메시지(100자 이하, 선택)") String message) {
}
