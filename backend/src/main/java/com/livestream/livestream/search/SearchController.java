package com.livestream.livestream.search;

import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.search.dto.SearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Tag(name = "Search", description = "통합 검색(채널 · 라이브 · 다시보기)")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

	private final SearchService searchService;

	@Operation(summary = "통합 검색 요약",
			description = "누구나. q는 trim · 연속 공백 정규화 뒤 1~50자(아니면 400). 채널 · 라이브 · 다시보기를 각각 최대 5개 + 전체 수")
	@GetMapping
	public SearchResponse summary(@RequestParam String q) {
		return searchService.summary(q);
	}

	@Operation(summary = "한 종류의 검색 결과 전체",
			description = "type = channels | lives | recordings (그 밖의 값은 404). page · size(최대 50) 페이징")
	@GetMapping("/{type}")
	public PageResponse<?> page(@PathVariable String type, @RequestParam String q,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size)
			throws NoResourceFoundException {
		SearchType searchType = SearchType.fromPath(type)
				.orElseThrow(() -> new NoResourceFoundException(HttpMethod.GET, "/api/search/" + type, type));
		return searchService.page(searchType, q, page, size);
	}
}
