package com.livestream.livestream.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** DESIGN §4.4 에러 코드 표. 화면 문구는 message, 분기는 code로 한다. */
@Getter
public enum ErrorCode {
	VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다"),

	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다"),
	TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다"),
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),

	FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),

	MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다"),
	CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "채널을 찾을 수 없습니다"),
	BROADCAST_NOT_FOUND(HttpStatus.NOT_FOUND, "방송을 찾을 수 없습니다"),
	RECORDING_NOT_FOUND(HttpStatus.NOT_FOUND, "다시보기를 찾을 수 없습니다"),
	POST_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다"),
	COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다"),
	DONATION_NOT_FOUND(HttpStatus.NOT_FOUND, "후원 내역을 찾을 수 없습니다"),

	EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다"),
	NICKNAME_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다"),

	BROADCAST_NOT_LIVE(HttpStatus.CONFLICT, "방송 중이 아닙니다"),
	BROADCAST_ALREADY_ENDED(HttpStatus.CONFLICT, "이미 종료된 방송입니다"),
	RECORDING_NOT_READY(HttpStatus.CONFLICT, "다시보기가 아직 준비되지 않았습니다"),
	COMMENT_DEPTH_EXCEEDED(HttpStatus.CONFLICT, "대댓글에는 답글을 달 수 없습니다"),
	RECORDING_COMMENT_NOT_OPEN(HttpStatus.CONFLICT, "댓글을 작성할 수 없는 다시보기입니다"),

	DONATION_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "후원 금액이 일치하지 않습니다"),
	DONATION_ALREADY_PROCESSED(HttpStatus.CONFLICT, "이미 처리된 후원입니다"),
	PAYMENT_CONFIRM_FAILED(HttpStatus.BAD_GATEWAY, "결제 승인에 실패했습니다"),

	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다");

	private final HttpStatus status;
	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}
}
