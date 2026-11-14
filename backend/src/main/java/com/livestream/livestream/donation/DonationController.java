package com.livestream.livestream.donation;

import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.donation.dto.DonationConfirmRequest;
import com.livestream.livestream.donation.dto.DonationCreateRequest;
import com.livestream.livestream.donation.dto.DonationCreateResponse;
import com.livestream.livestream.donation.dto.DonationPageResponse;
import com.livestream.livestream.donation.dto.DonationResponse;
import com.livestream.livestream.donation.dto.ReceivedDonationsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Donation", description = "후원 (Toss 결제위젯 v2, 테스트 키)")
@RestController
@RequiredArgsConstructor
public class DonationController {

	private final DonationService donationService;

	@Operation(summary = "후원 주문 생성", description = "로그인 · 방송 LIVE/DISCONNECTED. 금액 1000~100000원 → 201 결제위젯 주문 정보. 종료된 방송은 409")
	@PostMapping("/api/broadcasts/{id}/donations")
	public ResponseEntity<DonationCreateResponse> create(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@Valid @RequestBody DonationCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(donationService.create(me, id, request));
	}

	@Operation(summary = "결제 승인", description = "로그인 · 본인 후원만. 결제위젯 성공 콜백의 {paymentKey, orderId, amount}. "
			+ "같은 요청을 다시 보내면 같은 응답(멱등). 금액 불일치 400, 이미 실패 409, Toss 실패 502(결과 모름이면 다시 보내도 됨)")
	@PostMapping("/api/donations/confirm")
	public DonationResponse confirm(@CurrentMember MemberPrincipal me, @Valid @RequestBody DonationConfirmRequest request) {
		return donationService.confirm(me, request);
	}

	@Operation(summary = "방송 후원 목록", description = "누구나. 승인된 후원만, 최신순 + 합계")
	@GetMapping("/api/broadcasts/{id}/donations")
	public DonationPageResponse list(@PathVariable Long id, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return donationService.list(id, page, size);
	}

	@Operation(summary = "받은 후원", description = "로그인. 내 채널이 받은 승인 후원 합계, 방송별")
	@GetMapping("/api/members/me/donations/received")
	public ReceivedDonationsResponse received(@CurrentMember MemberPrincipal me) {
		return donationService.received(me);
	}
}
