package com.livestream.livestream.search;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastService;
import com.livestream.livestream.broadcast.dto.BroadcastSummary;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.channel.dto.CategoryResponse;
import com.livestream.livestream.chat.ViewerCounter;
import com.livestream.livestream.comment.CommentCounter;
import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.follow.FollowRepository;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.dto.RecordingSummary;
import com.livestream.livestream.search.dto.ChannelSummary;
import com.livestream.livestream.search.dto.SearchGroup;
import com.livestream.livestream.search.dto.SearchQuery;
import com.livestream.livestream.search.dto.SearchResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 통합 검색 (DESIGN §4.16, §9 SEARCH-1). 채널 · 다시보기는 DB 에서 찾고 페이징하고, 라이브는 시청자 수가 메모리 값이라
 * {@code /broadcasts} 처럼 모두 읽어 메모리에서 정렬 · 자른다(진행 중 방송은 채널당 1건이라 수가 적다).
 */
@Service
@RequiredArgsConstructor
public class SearchService {

	/** 요약에서 종류별로 보여 주는 개수. */
	static final int SUMMARY_SIZE = 5;

	private final ChannelRepository channelRepository;
	private final BroadcastRepository broadcastRepository;
	private final RecordingRepository recordingRepository;
	private final FollowRepository followRepository;
	private final ViewerCounter viewerCounter;
	private final CommentCounter commentCounter;

	@Transactional(readOnly = true)
	public SearchResponse summary(String raw) {
		SearchQuery query = SearchQuery.of(raw);
		Pageable first = PageRequest.of(0, SUMMARY_SIZE);
		Page<ChannelSummary> channels = channels(query, first);
		List<BroadcastSummary> lives = lives(query);
		Page<RecordingSummary> recordings = recordings(query, first);
		return new SearchResponse(query.q(),
				new SearchGroup<>(channels.getContent(), channels.getTotalElements()),
				new SearchGroup<>(lives.subList(0, Math.min(SUMMARY_SIZE, lives.size())), lives.size()),
				new SearchGroup<>(recordings.getContent(), recordings.getTotalElements()));
	}

	@Transactional(readOnly = true)
	public PageResponse<?> page(SearchType type, String raw, Integer page, Integer size) {
		SearchQuery query = SearchQuery.of(raw);
		Pageable pageable = PageResponse.pageable(page, size, Sort.unsorted());
		return switch (type) {
			case CHANNELS -> PageResponse.of(channels(query, pageable), Function.identity());
			case LIVES -> PageResponse.of(livesPage(lives(query), pageable), Function.identity());
			case RECORDINGS -> PageResponse.of(recordings(query, pageable), Function.identity());
		};
	}

	// ---------- 종류별 ----------

	/** 이름 오름차순. isLive · viewerCount · followerCount 는 이 페이지의 채널 id 로 한 번씩만 묻는다(N+1 없음). */
	private Page<ChannelSummary> channels(SearchQuery query, Pageable pageable) {
		Page<Channel> found = channelRepository.search(query.pattern(), pageable);
		List<Long> ids = found.getContent().stream().map(Channel::getId).toList();
		Map<Long, Broadcast> live = new HashMap<>();
		if (!ids.isEmpty()) {
			for (Broadcast b : broadcastRepository.findOngoingByChannelIds(ids)) {
				live.merge(b.getChannel().getId(), b, (a, c) -> a.getId() >= c.getId() ? a : c);
			}
		}
		Map<Long, Long> followers = followRepository.countByChannelIds(ids);
		return found.map(c -> {
			Broadcast b = live.get(c.getId());
			return new ChannelSummary(c.getId(), c.getName(), c.getDescription(), CategoryResponse.of(c.getCategory()),
					b != null, b == null ? null : b.getId(), b == null ? 0 : viewerCounter.count(b.getId()),
					followers.getOrDefault(c.getId(), 0L));
		});
	}

	/** {@code /broadcasts} 와 같은 정렬: 시청자 수 → 시작 시각 → id 내림차순. */
	private List<BroadcastSummary> lives(SearchQuery query) {
		return broadcastRepository.searchOngoing(query.pattern()).stream()
				.map(b -> BroadcastSummary.of(b, viewerCounter.count(b.getId())))
				.sorted(BroadcastService.POPULAR_FIRST)
				.toList();
	}

	private static Page<BroadcastSummary> livesPage(List<BroadcastSummary> all, Pageable pageable) {
		int from = (int) Math.min(pageable.getOffset(), all.size());
		int to = Math.min(from + pageable.getPageSize(), all.size());
		return new PageImpl<>(all.subList(from, to), pageable, all.size());
	}

	/** 공개 다시보기만, 방송 시작 최신순. */
	private Page<RecordingSummary> recordings(SearchQuery query, Pageable pageable) {
		Page<Recording> found = recordingRepository.searchPublic(query.pattern(), pageable);
		Map<Long, Long> comments = commentCounter.ofRecordings(found.map(Recording::getId).getContent());
		return found.map(r -> RecordingSummary.of(r, comments.getOrDefault(r.getId(), 0L)));
	}
}
