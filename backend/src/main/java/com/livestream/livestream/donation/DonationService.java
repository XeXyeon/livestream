package com.livestream.livestream.donation;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.chat.BroadcastEventPublisher;
import com.livestream.livestream.chat.dto.DonationMessage;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.donation.dto.DonationConfirmRequest;
import com.livestream.livestream.donation.dto.DonationCreateRequest;
import com.livestream.livestream.donation.dto.DonationCreateResponse;
import com.livestream.livestream.donation.dto.DonationPageResponse;
import com.livestream.livestream.donation.dto.DonationResponse;
import com.livestream.livestream.donation.dto.ReceivedDonationByBroadcast;
import com.livestream.livestream.donation.dto.ReceivedDonationsResponse;
import com.livestream.livestream.member.Member;
import com.livestream.livestream.member.MemberRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 후원 (DESIGN §4.13). 생성(PENDING) → 결제위젯 결제 → confirm(주문 행 잠금 → Toss 승인 → APPROVED) → 이벤트.
 * <p>
 * confirm 은 같은 주문을 {@code SELECT … FOR UPDATE} 로 줄 세워 Toss 를 한 번만 부른다. 상태를 FAILED 로 바꾸는 실패는
 * 예외로 롤백되면 안 되므로 트랜잭션 안에서는 결과만 돌려주고, 커밋 뒤에 예외를 던진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DonationService {

	static final int AMOUNT_MIN = 1_000;
	static final int AMOUNT_MAX = 100_000;
	static final int MESSAGE_MAX = 100;
	/** 회원별 최근 결제 대기 주문 상한(창 {@link #PENDING_WINDOW}). 결제위젯을 열었다 닫으면 PENDING 이 남으므로 넉넉히 둔다. */
	static final int PENDING_LIMIT = 5;
	static final java.time.Duration PENDING_WINDOW = java.time.Duration.ofMinutes(10);
	private static final String ORDER_NAME = "후원";
	/** 채팅과 같은 금지 문자: 제어문자, 줄 · 문단 구분자, 양방향 재정렬 문자. */
	private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[\\p{Cc}\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]");

	private final DonationRepository donationRepository;
	private final BroadcastRepository broadcastRepository;
	private final MemberRepository memberRepository;
	private final TossPaymentsClient tossPaymentsClient;
	private final BroadcastEventPublisher eventPublisher;
	private final TransactionTemplate tx;
	private final AppProperties props;

	/** POST /broadcasts/{id}/donations — 로그인 · 방송 LIVE/DISCONNECTED → PENDING 주문. */
	@Transactional
	public DonationCreateResponse create(MemberPrincipal me, Long broadcastId, DonationCreateRequest request) {
		requireLogin(me);
		int amount = amount(request.amount());
		String message = message(request.message());
		Broadcast broadcast = broadcastRepository.findActive(broadcastId)
				.orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
		if (!broadcast.getStatus().isOngoing()) {
			throw new ApiException(ErrorCode.BROADCAST_NOT_LIVE);
		}
		Member donor = memberRepository.findById(me.id()).orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));
		if (donationRepository.countRecentPending(me.id(), Instant.now().minus(PENDING_WINDOW)) >= PENDING_LIMIT) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "결제 대기 중인 후원이 너무 많습니다. 잠시 후 다시 시도해 주세요");
		}
		Donation d = donationRepository.save(Donation.pending(broadcast, donor, amount, message, UUID.randomUUID().toString()));
		return new DonationCreateResponse(d.getId(), d.getOrderId(), amount, props.toss().clientKey(), ORDER_NAME,
				donor.getNickname());
	}

	/**
	 * POST /donations/confirm (§4.13 3). 본인 후원만(남의 주문은 404). 이미 APPROVED 면 같은 응답(멱등), FAILED 면 409, 금액이 다르면 FAILED + 400,
	 * Toss 거절이면 FAILED + 502, 결과를 모르면(타임아웃 등) PENDING 그대로 502 — 같은 요청으로 다시 confirm 할 수 있다.
	 */
	public DonationResponse confirm(MemberPrincipal me, DonationConfirmRequest request) {
		requireLogin(me);
		ConfirmResult result = tx.execute(s -> confirmLocked(me, request));
		if (result.error() != null) {
			throw result.error();
		}
		if (result.newlyApproved()) {
			publishApproved(result.response(), result.broadcastOngoing());
		}
		return result.response();
	}

	/** GET /broadcasts/{id}/donations — 승인된 후원(공개), 최신순 + 합계. 삭제된 방송은 404. */
	@Transactional(readOnly = true)
	public DonationPageResponse list(Long broadcastId, Integer page, Integer size) {
		if (broadcastRepository.findActive(broadcastId).isEmpty()) {
			throw new ApiException(ErrorCode.BROADCAST_NOT_FOUND);
		}
		Page<Donation> p = donationRepository.findApprovedByBroadcast(broadcastId, PageResponse.pageable(page, size, Sort.unsorted()));
		PageResponse<DonationResponse> items = PageResponse.of(p, DonationResponse::of);
		return new DonationPageResponse(items.items(), items.page(), items.size(), items.totalElements(), items.hasNext(),
				donationRepository.sumApprovedByBroadcast(broadcastId), p.getTotalElements());
	}

	/** GET /members/me/donations/received — 내 채널이 받은 승인 후원, 방송별(삭제된 방송 포함). */
	@Transactional(readOnly = true)
	public ReceivedDonationsResponse received(MemberPrincipal me) {
		requireLogin(me);
		List<ReceivedDonationByBroadcast> byBroadcast = donationRepository.sumApprovedByBroadcastOfChannel(me.channelId());
		long total = byBroadcast.stream().mapToLong(ReceivedDonationByBroadcast::totalAmount).sum();
		return new ReceivedDonationsResponse(total, byBroadcast);
	}

	// ---------- 내부 ----------

	/** 트랜잭션 안: 주문 행 잠금이 첫 읽기다. 상태를 바꾸는 실패는 예외 대신 결과로 돌려 커밋되게 한다. */
	private ConfirmResult confirmLocked(MemberPrincipal me, DonationConfirmRequest request) {
		Donation d = donationRepository.findByOrderIdForUpdate(request.orderId())
				.orElseThrow(() -> new ApiException(ErrorCode.DONATION_NOT_FOUND));
		if (!d.isDonatedBy(me.id())) {
			throw new ApiException(ErrorCode.DONATION_NOT_FOUND); // 남의 주문도 없는 주문처럼(존재 여부를 드러내지 않는다)
		}
		switch (d.getStatus()) {
			case APPROVED -> {
				if (request.paymentKey().equals(d.getPaymentKey()) && request.amount() == d.getAmount()) {
					return ConfirmResult.ok(DonationResponse.of(d), false);
				}
				throw new ApiException(ErrorCode.DONATION_ALREADY_PROCESSED);
			}
			case FAILED -> throw new ApiException(ErrorCode.DONATION_ALREADY_PROCESSED);
			case PENDING -> {
				// 아래로
			}
		}
		if (request.amount() != d.getAmount()) {
			log.warn("후원 금액 불일치 donationId={} 요청={} 저장={}", d.getId(), request.amount(), d.getAmount());
			d.fail();
			return ConfirmResult.failed(new ApiException(ErrorCode.DONATION_AMOUNT_MISMATCH));
		}
		TossPaymentsClient.Result toss = tossPaymentsClient.confirm(request.paymentKey(), d.getOrderId(), d.getAmount());
		return switch (toss.outcome()) {
			case APPROVED -> {
				d.approve(request.paymentKey(), Instant.now());
				donationRepository.flush(); // payment_key 유니크 위반을 여기서 드러낸다
				yield ConfirmResult.approved(DonationResponse.of(d), d.getBroadcast().getStatus().isOngoing());
			}
			case REJECTED -> {
				log.info("Toss 승인 거절 donationId={} code={}", d.getId(), toss.code());
				d.fail();
				yield ConfirmResult.failed(paymentFailed(toss.message()));
			}
			case UNKNOWN -> ConfirmResult.failed(paymentFailed(
					toss.message() != null ? toss.message() : "결제 확인이 지연되고 있습니다. 잠시 후 다시 시도해 주세요"));
		};
	}

	/** 채팅 토픽 · 버퍼는 방송이 진행 중일 때만(종료 뒤 승인되면 볼 시청자가 없고, 버퍼가 만료 예약 없이 다시 생긴다). */
	private void publishApproved(DonationResponse r, boolean broadcastOngoing) {
		if (!broadcastOngoing) {
			return;
		}
		try {
			eventPublisher.donation(r.broadcastId(),
					DonationMessage.of(r.id(), r.donor().nickname(), r.amount(), r.message(), r.approvedAt()));
		} catch (RuntimeException e) {
			log.warn("후원 이벤트 발행 실패 donationId={}: {}", r.id(), e.toString());
		}
	}

	private static ApiException paymentFailed(String tossMessage) {
		return tossMessage == null ? new ApiException(ErrorCode.PAYMENT_CONFIRM_FAILED)
				: new ApiException(ErrorCode.PAYMENT_CONFIRM_FAILED, ErrorCode.PAYMENT_CONFIRM_FAILED.getMessage() + ": " + tossMessage);
	}

	private static void requireLogin(MemberPrincipal me) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
	}

	static int amount(Integer raw) {
		if (raw == null || raw < AMOUNT_MIN || raw > AMOUNT_MAX) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "후원 금액은 " + AMOUNT_MIN + "원 이상 " + AMOUNT_MAX + "원 이하여야 합니다");
		}
		return raw;
	}

	/** 앞뒤 공백을 지우고 비면 null. 100자(코드포인트) 이하, 금지 문자 없음. */
	static String message(String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.strip();
		if (value.isEmpty()) {
			return null;
		}
		if (FORBIDDEN_CHARS.matcher(value).find()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "메시지에 사용할 수 없는 문자가 있습니다");
		}
		if (value.codePointCount(0, value.length()) > MESSAGE_MAX) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "메시지는 " + MESSAGE_MAX + "자 이하여야 합니다");
		}
		return value;
	}

	private record ConfirmResult(DonationResponse response, boolean newlyApproved, boolean broadcastOngoing, ApiException error) {

		static ConfirmResult ok(DonationResponse response, boolean newlyApproved) {
			return new ConfirmResult(response, newlyApproved, false, null);
		}

		static ConfirmResult approved(DonationResponse response, boolean broadcastOngoing) {
			return new ConfirmResult(response, true, broadcastOngoing, null);
		}

		static ConfirmResult failed(ApiException error) {
			return new ConfirmResult(null, false, false, error);
		}
	}
}
