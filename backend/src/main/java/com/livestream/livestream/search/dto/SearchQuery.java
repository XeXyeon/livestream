package com.livestream.livestream.search.dto;

import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import java.util.regex.Pattern;

/**
 * 정규화된 검색어와 LIKE 패턴 (DESIGN §4.16). {@code q} 는 앞뒤 공백을 떼고 연속 공백을 한 칸으로 줄인 값이고,
 * {@code pattern} 은 {@code %q%} 에서 {@code \ % _} 를 {@code \} 로 이스케이프해 글자로 찾게 만든 값이다.
 */
public record SearchQuery(String q, String pattern) {

	public static final int MAX_LENGTH = 50;
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	public static SearchQuery of(String raw) {
		String q = raw == null ? "" : WHITESPACE.matcher(raw.strip()).replaceAll(" ");
		int length = q.codePointCount(0, q.length());
		if (length < 1 || length > MAX_LENGTH) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "검색어는 1~" + MAX_LENGTH + "자");
		}
		String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return new SearchQuery(q, "%" + escaped + "%");
	}
}
