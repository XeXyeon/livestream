package com.livestream.livestream.common.security;

import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Security 필터 단계(컨트롤러 전)에서 {@link ErrorResponse} JSON을 직접 쓴다. 필드가 문자열 둘뿐이라 직접 이스케이프한다. */
final class ErrorResponseWriter {

	private ErrorResponseWriter() {
	}

	static void write(HttpServletResponse response, ErrorCode code) throws IOException {
		ErrorResponse body = ErrorResponse.of(code);
		response.setStatus(code.getStatus().value());
		response.setContentType("application/json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"code\":\"" + escape(body.code()) + "\",\"message\":\"" + escape(body.message()) + "\"}");
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
