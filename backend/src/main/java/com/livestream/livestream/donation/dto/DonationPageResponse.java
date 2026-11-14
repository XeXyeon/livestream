package com.livestream.livestream.donation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "방송의 승인된 후원 목록 + 합계")
public record DonationPageResponse(
		List<DonationResponse> items,
		int page,
		int size,
		long totalElements,
		boolean hasNext,
		@Schema(description = "승인 합계(원)") long totalAmount,
		@Schema(description = "승인 건수") long totalCount) {
}
