package com.livestream.livestream.broadcast;

import com.livestream.livestream.broadcast.dto.BroadcastSummary;
import com.livestream.livestream.broadcast.dto.Recommendations;
import com.livestream.livestream.chat.ViewerCounter;
import com.livestream.livestream.comment.CommentCounter;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.dto.RecordingSummary;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 추천 (DESIGN §4.16 · 흐름 "방송 종료" 시청자 단계): 다른 채널의 같은 카테고리 LIVE 를 시청자 수 순으로 최대 4개,
 * 하나도 없으면 최근 공개 다시보기 4개.
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

	static final int LIMIT = 4;

	private final BroadcastRepository broadcastRepository;
	private final RecordingRepository recordingRepository;
	private final ViewerCounter viewerCounter;
	private final CommentCounter commentCounter;

	/** 호출 쪽 트랜잭션(readOnly) 안에서 부른다. */
	public Recommendations recommend(Broadcast base) {
		List<BroadcastSummary> lives = broadcastRepository
				.findLiveForRecommendation(base.getCategory(), base.getChannel().getId()).stream()
				.map(b -> BroadcastSummary.of(b, viewerCounter.count(b.getId())))
				.sorted(BroadcastService.POPULAR_FIRST)
				.limit(LIMIT)
				.toList();
		if (!lives.isEmpty()) {
			return new Recommendations(lives, List.of());
		}
		List<Recording> recent = recordingRepository.findRecentPublic(base.getId(), PageRequest.of(0, LIMIT));
		Map<Long, Long> comments = commentCounter.ofRecordings(recent.stream().map(Recording::getId).toList());
		List<RecordingSummary> recordings = recent.stream()
				.map(r -> RecordingSummary.of(r, comments.getOrDefault(r.getId(), 0L)))
				.toList();
		return new Recommendations(List.of(), recordings);
	}
}
