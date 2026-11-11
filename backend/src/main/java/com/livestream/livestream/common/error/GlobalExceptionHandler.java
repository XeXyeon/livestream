package com.livestream.livestream.common.error;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
		ErrorCode code = e.getErrorCode();
		return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, e.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e) {
		String message = e.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(fe -> fe.getDefaultMessage())
				.orElse(ErrorCode.VALIDATION_ERROR.getMessage());
		return validation(message);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException e) {
		String message = e.getConstraintViolations().stream()
				.findFirst()
				.map(v -> v.getMessage())
				.orElse(ErrorCode.VALIDATION_ERROR.getMessage());
		return validation(message);
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
			MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
		return validation(ErrorCode.VALIDATION_ERROR.getMessage());
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
		return ResponseEntity.status(405).body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, "지원하지 않는 요청 방식입니다"));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
		return ResponseEntity.status(404).body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, "존재하지 않는 경로입니다"));
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
		return ResponseEntity.status(415).body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, "지원하지 않는 Content-Type입니다"));
	}

	@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
	public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
		return ResponseEntity.status(406).body(ErrorResponse.of(ErrorCode.VALIDATION_ERROR, "지원하지 않는 Accept 입니다"));
	}

	@ExceptionHandler({MissingRequestHeaderException.class, HandlerMethodValidationException.class})
	public ResponseEntity<ErrorResponse> handleMissingHeaderOrMethodValidation(Exception e) {
		return validation(ErrorCode.VALIDATION_ERROR.getMessage());
	}

	/** 보안 예외는 500으로 삼키지 않고 Security 필터(ExceptionTranslationFilter)가 401/403으로 처리하게 다시 던진다. */
	@ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
	public void rethrowSecurity(Exception e) throws Exception {
		throw e;
	}

	/** 클라이언트가 끊은 비동기 응답(SSE 등): 쓸 수 없는 응답이므로 아무것도 쓰지 않고 로그도 남기지 않는다. */
	@ExceptionHandler(AsyncRequestNotUsableException.class)
	public void handleDisconnected(AsyncRequestNotUsableException e) {
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
		log.error("처리되지 않은 예외", e);
		ErrorCode code = ErrorCode.INTERNAL_ERROR;
		return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code));
	}

	private ResponseEntity<ErrorResponse> validation(String message) {
		ErrorCode code = ErrorCode.VALIDATION_ERROR;
		return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, message));
	}
}
