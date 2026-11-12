package com.livestream.livestream.follow;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.channel.dto.CategoryResponse;
import com.livestream.livestream.chat.ViewerCounter;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.follow.dto.FollowStatusResponse;
import com.livestream.livestream.follow.dto.FollowedChannelResponse;
import com.livestream.livestream.member.MemberRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 채널 팔로우 · 해제 · 내 팔로우 목록 (DESIGN §9 FOLLOW-1). 둘 다 멱등이다. */
@Service
@RequiredArgsConstructor
public class FollowService {

	private final FollowRepository followRepository;
	private final ChannelRepository channelRepository;
	private final MemberRepository memberRepository;
	private final BroadcastRepository broadcastRepository;
	private final ViewerCounter viewerCounter;

	/**
	 * 바깥 트랜잭션을 만들지 않는다: 저장소 호출이 각자 짧은 트랜잭션으로 돈다. 그래서 동시 요청의 unique 위반이
	 * rollback-only 를 남기지 않고, 마지막 count 도 방금 커밋된 행을 본다(MySQL REPEATABLE READ 스냅샷 문제 없음).
	 */
	public FollowStatusResponse follow(MemberPrincipal me, Long channelId) {
		Channel channel = requireFollowable(me, channelId);
		if (!followRepository.existsByMemberIdAndChannelId(me.id(), channelId)) {
			try {
				followRepository.saveAndFlush(Follow.of(memberRepository.getReferenceById(me.id()), channel));
			} catch (DataIntegrityViolationException e) {
				// 동시 요청이 먼저 저장했으면(unique 위반) 이미 팔로우 중으로 본다. 행이 없으면 FK 위반 등 다른 원인이므로 그대로 올린다.
				if (!followRepository.existsByMemberIdAndChannelId(me.id(), channelId)) {
					throw e;
				}
			}
		}
		return new FollowStatusResponse(channelId, true, followRepository.countByChannelId(channelId));
	}

	@Transactional
	public FollowStatusResponse unfollow(MemberPrincipal me, Long channelId) {
		requireFollowable(me, channelId);
		followRepository.deleteByMemberIdAndChannelId(me.id(), channelId);
		return new FollowStatusResponse(channelId, false, followRepository.countByChannelId(channelId));
	}

	/** 라이브 먼저(시청자 수 내림차순 → 방송 시작 내림차순), 그다음 팔로우한 순(최신 먼저). */
	@Transactional(readOnly = true)
	public List<FollowedChannelResponse> myFollows(MemberPrincipal me) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		List<Follow> follows = followRepository.findAllByMemberIdOrderByCreatedAtDesc(me.id());
		if (follows.isEmpty()) {
			return List.of();
		}
		List<Long> channelIds = follows.stream().map(f -> f.getChannel().getId()).toList();
		Map<Long, Broadcast> live = new HashMap<>();
		for (Broadcast b : broadcastRepository.findOngoingByChannelIds(channelIds)) {
			live.merge(b.getChannel().getId(), b, (a, c) -> a.getId() >= c.getId() ? a : c);
		}
		Map<Long, Long> viewers = new HashMap<>();
		live.values().forEach(b -> viewers.put(b.getId(), viewerCounter.count(b.getId())));

		record Row(FollowedChannelResponse response, Broadcast live) {
		}
		// follows 가 이미 팔로우 최신순이고 sort 는 안정 정렬이라, 라이브가 아닌 항목은 그 순서가 유지된다.
		return follows.stream()
				.map(f -> {
					Channel c = f.getChannel();
					Broadcast b = live.get(c.getId());
					return new Row(new FollowedChannelResponse(c.getId(), c.getName(), CategoryResponse.of(c.getCategory()),
							b != null, b == null ? null : b.getId(), b == null ? 0 : viewers.get(b.getId()),
							f.getCreatedAt()), b);
				})
				.sorted(Comparator
						.comparing((Row r) -> r.live() == null)
						.thenComparing((Row r) -> r.live() == null ? 0L : -r.response().viewerCount())
						.thenComparing((Row r) -> r.live() == null ? java.time.Instant.MIN : r.live().getStartedAt(),
								Comparator.reverseOrder()))
				.map(Row::response)
				.toList();
	}

	private Channel requireFollowable(MemberPrincipal me, Long channelId) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		Channel channel = channelRepository.findById(channelId)
				.orElseThrow(() -> new ApiException(ErrorCode.CHANNEL_NOT_FOUND));
		if (channel.getMember().getId().equals(me.id())) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "자기 채널은 팔로우할 수 없습니다");
		}
		return channel;
	}
}
