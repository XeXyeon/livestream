package com.livestream.livestream.common.error;

import lombok.Getter;

/** 429 {@code TOO_MANY_REQUESTS}. 응답에 {@code Retry-After: <retryAfterSeconds>} 헤더가 붙는다(DESIGN §4.4). */
@Getter
public class TooManyRequestsException extends ApiException {

	private final long retryAfterSeconds;

	public TooManyRequestsException(long retryAfterSeconds) {
		super(ErrorCode.TOO_MANY_REQUESTS);
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}
}
