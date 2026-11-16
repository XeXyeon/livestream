package com.livestream.livestream.search.dto;

import com.livestream.livestream.broadcast.dto.BroadcastSummary;
import com.livestream.livestream.recording.dto.RecordingSummary;
import io.swagger.v3.oas.annotations.media.Schema;

/** GET /search 응답 (DESIGN §4.16). */
@Schema(description = "통합 검색 요약")
public record SearchResponse(
		@Schema(description = "정규화된 검색어") String q,
		SearchGroup<ChannelSummary> channels,
		SearchGroup<BroadcastSummary> lives,
		SearchGroup<RecordingSummary> recordings) {
}
