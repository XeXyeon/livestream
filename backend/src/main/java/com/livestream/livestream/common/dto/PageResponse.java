package com.livestream.livestream.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** DESIGN §4.4 목록 응답 {@code { items, page, size, totalElements, hasNext }}. */
@Schema(description = "목록 응답")
public record PageResponse<T>(
		List<T> items,
		@Schema(description = "0부터 시작하는 페이지 번호") int page,
		int size,
		long totalElements,
		boolean hasNext) {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 50;

	public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
		List<T> items = page.getContent().stream().<T>map(mapper).toList();
		return new PageResponse<>(items, page.getNumber(), page.getSize(), page.getTotalElements(), page.hasNext());
	}

	/** 쿼리 파라미터(page · size)를 안전한 범위로 바꾼다: page는 0 이상, size는 1~{@link #MAX_SIZE}, 없으면 20. */
	public static Pageable pageable(Integer page, Integer size, Sort sort) {
		int p = page == null || page < 0 ? 0 : page;
		int s = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
		return PageRequest.of(p, s, sort);
	}
}
