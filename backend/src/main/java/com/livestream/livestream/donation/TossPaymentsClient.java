package com.livestream.livestream.donation;

/**
 * Toss 결제 승인 (DESIGN §4.13 · §4.17: 외부 연동은 인터페이스 뒤에 두고 테스트에서 mock).
 */
public interface TossPaymentsClient {

	Result confirm(String paymentKey, String orderId, long amount);

	enum Outcome {
		/** 2xx 이고 결제 status=DONE. */
		APPROVED,
		/** Toss 가 4xx 로 확정 거절(카드 거절 · 잔액 부족 등). 후원은 FAILED. 일시적 4xx(409 · 429 · 이미 처리됨 등)는 UNKNOWN. */
		REJECTED,
		/** 타임아웃 · 연결 실패 · 5xx · 일시적 4xx · DONE 이 아닌 2xx — 승인됐는지 모른다. 후원은 PENDING 으로 두고 재시도를 받는다. */
		UNKNOWN
	}

	/** @param code Toss 에러 코드(없으면 null), @param message 사용자에게 보여 줄 수 있는 Toss 메시지(없으면 null) */
	record Result(Outcome outcome, String code, String message) {

		public static Result approved() {
			return new Result(Outcome.APPROVED, null, null);
		}
	}
}
