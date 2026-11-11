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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

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

	/** 방송 채널 주인의 member id (시청자 수에서 주인의 구독을 빼려고, CHAT-1). 삭제된 방송도 찾는다(주인은 바뀌지 않는다). */
	@Query("select b.channel.member.id from Broadcast b where b.id = :id")
	Optional<Long> findOwnerMemberIdOf(Long id);

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

	// ---------- BC-3 썸네일 ----------

	/** 썸네일 캡처 대상: 삭제되지 않은 LIVE 방송의 (방송 id, 채널 id). */
	@Query("""
			select new com.livestream.livestream.broadcast.LiveBroadcastRef(b.id, b.channel.id) from Broadcast b
			where b.status = com.livestream.livestream.broadcast.BroadcastStatus.LIVE and b.deletedAt is null
			""")
	List<LiveBroadcastRef> findLiveRefs();

	@Query("""
			select count(b) > 0 from Broadcast b
			where b.id = :id and b.status = com.livestream.livestream.broadcast.BroadcastStatus.LIVE and b.deletedAt is null
			""")
	boolean isLive(Long id);

	/** 처음 성공했을 때만 경로를 채운다(이후 캡처는 파일만 덮어씀). 삭제된 방송은 건드리지 않는다. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("update Broadcast b set b.thumbnailPath = :path where b.id = :id and b.thumbnailPath is null and b.deletedAt is null")
	int assignThumbnailPathIfAbsent(Long id, String path);
}
