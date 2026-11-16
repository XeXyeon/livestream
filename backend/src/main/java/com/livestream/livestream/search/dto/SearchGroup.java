package com.livestream.livestream.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 통합 검색 요약의 한 종류: 앞쪽 몇 개와 전체 수. */
@Schema(description = "검색 결과 한 종류(앞쪽 최대 5개 + 전체 수)")
public record SearchGroup<T>(List<T> items, @Schema(description = "조건에 맞는 전체 수") long total) {
}
