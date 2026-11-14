package com.livestream.livestream.broadcast.dto;

import com.livestream.livestream.recording.dto.RecordingSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** GET /broadcasts/{id}/recommendations — 다른 채널 같은 카테고리 라이브, 없으면 최근 공개 다시보기. */
@Schema(description = "추천: lives 가 비어 있을 때만 recordings 를 채운다")
public record Recommendations(List<BroadcastSummary> lives, List<RecordingSummary> recordings) {
}
