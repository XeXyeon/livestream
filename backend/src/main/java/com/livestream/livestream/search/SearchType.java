package com.livestream.livestream.search;

import java.util.Arrays;
import java.util.Optional;

/** GET /search/{type} 의 경로 값 (DESIGN §4.16). */
public enum SearchType {
	CHANNELS("channels"),
	LIVES("lives"),
	RECORDINGS("recordings");

	private final String path;

	SearchType(String path) {
		this.path = path;
	}

	public String path() {
		return path;
	}

	/** 경로 값(소문자 정확히 일치)으로 찾는다. 없으면 빈 값 — 호출한 쪽이 404 로 바꾼다. */
	public static Optional<SearchType> fromPath(String path) {
		return Arrays.stream(values()).filter(t -> t.path.equals(path)).findFirst();
	}
}
