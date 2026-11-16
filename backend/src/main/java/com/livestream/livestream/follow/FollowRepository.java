package com.livestream.livestream.follow;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface FollowRepository extends JpaRepository<Follow, Long> {

	boolean existsByMemberIdAndChannelId(Long memberId, Long channelId);

	long countByChannelId(Long channelId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from Follow f where f.member.id = :memberId and f.channel.id = :channelId")
	int deleteByMemberIdAndChannelId(Long memberId, Long channelId);

	/** 내가 팔로우한 채널, 팔로우한 순(최신 먼저). */
	@Query("""
			select f from Follow f join fetch f.channel
			where f.member.id = :memberId
			order by f.createdAt desc, f.id desc
			""")
	List<Follow> findAllByMemberIdOrderByCreatedAtDesc(Long memberId);

	@Query("select f.channel.id, count(f) from Follow f where f.channel.id in :channelIds group by f.channel.id")
	List<Object[]> countRowsByChannelIds(Collection<Long> channelIds);

	/** 채널별 팔로워 수를 한 번에(검색 결과 카드, SEARCH-1). 팔로워가 없는 채널은 맵에 없다. */
	default Map<Long, Long> countByChannelIds(Collection<Long> channelIds) {
		Map<Long, Long> counts = new HashMap<>();
		if (!channelIds.isEmpty()) {
			for (Object[] row : countRowsByChannelIds(channelIds)) {
				counts.put((Long) row[0], (Long) row[1]);
			}
		}
		return counts;
	}
}
