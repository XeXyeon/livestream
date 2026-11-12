package com.livestream.livestream.follow;

import java.util.List;
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
}
