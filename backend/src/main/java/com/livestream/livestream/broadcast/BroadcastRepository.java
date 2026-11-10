package com.livestream.livestream.broadcast;

import com.livestream.livestream.channel.Category;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

	/** 채널의 진행 중(LIVE · DISCONNECTED) 방송. 서비스 로직이 채널당 최대 1건을 보장한다. */
	@Query("""
			select b from Broadcast b
			where b.channel.id = :channelId and b.status in :statuses and b.deletedAt is null
			order by b.id desc
			""")
	List<Broadcast> findByChannelAndStatuses(Long channelId, Collection<BroadcastStatus> statuses);

	default Optional<Broadcast> findOngoing(Long channelId) {
		return findByChannelAndStatuses(channelId, List.of(BroadcastStatus.LIVE, BroadcastStatus.DISCONNECTED))
				.stream().findFirst();
	}

	@Query("select b from Broadcast b where b.id = :id and b.deletedAt is null")
	Optional<Broadcast> findActive(Long id);

	/** 상태 전이용 잠금 읽기. 반드시 채널 행을 먼저 잠근 뒤 부른다(잠금 순서 channel → broadcast). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select b from Broadcast b where b.id = :id and b.deletedAt is null")
	Optional<Broadcast> findActiveForUpdate(Long id);

	/** 엔티티를 영속성 컨텍스트에 올리지 않고 채널 id 만 읽는다(채널을 잠그기 전에 쓴다). */
	@Query("select b.channel.id from Broadcast b where b.id = :id and b.deletedAt is null")
	Optional<Long> findChannelIdOf(Long id);

	/** 유예가 지난 DISCONNECTED 방송 id (DisconnectGraceScheduler). */
	@Query("""
			select b.id from Broadcast b
			where b.status = com.livestream.livestream.broadcast.BroadcastStatus.DISCONNECTED
			  and b.disconnectedAt < :before and b.deletedAt is null
			""")
	List<Long> findDisconnectedBefore(Instant before);

	/** 목록용: 상태 · 카테고리(null 이면 전체). 진행 중 방송은 수가 적어 시청자 수 정렬을 메모리에서 한다. */
	@Query("""
			select b from Broadcast b join fetch b.channel
			where b.status = :status and b.deletedAt is null and (:category is null or b.category = :category)
			""")
	List<Broadcast> findAllByStatus(BroadcastStatus status, Category category);

	/** 추천: 같은 카테고리의 다른 채널 LIVE 방송. */
	@Query("""
			select b from Broadcast b join fetch b.channel c
			where b.status = com.livestream.livestream.broadcast.BroadcastStatus.LIVE and b.deletedAt is null
			  and b.category = :category and c.id <> :excludeChannelId
			""")
	List<Broadcast> findLiveForRecommendation(Category category, Long excludeChannelId);

	/** 내 방송 기록(삭제 제외), 최신순. */
	@Query(value = """
			select b from Broadcast b join fetch b.channel c
			where c.member.id = :memberId and b.deletedAt is null
			order by b.startedAt desc, b.id desc
			""", countQuery = """
			select count(b) from Broadcast b where b.channel.member.id = :memberId and b.deletedAt is null
			""")
	Page<Broadcast> findMine(Long memberId, Pageable pageable);
}
