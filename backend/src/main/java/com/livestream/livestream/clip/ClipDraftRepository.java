package com.livestream.livestream.clip;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ClipDraftRepository extends JpaRepository<ClipDraft, Long> {

	/** 만든 회원의 초안만(다른 회원에게는 없는 것처럼 404). */
	@Query("select d from ClipDraft d join fetch d.broadcast where d.id = :id and d.member.id = :memberId")
	Optional<ClipDraft> findByIdAndMemberId(Long id, Long memberId);

	/** 새 초안을 만들 때 이전 초안을 지우려고 잠가 읽는다(회원당 초안 1개). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from ClipDraft d where d.member.id = :memberId")
	List<ClipDraft> findByMemberIdForUpdate(Long memberId);

	/** 잠그기 전에 이전 초안 id 만 읽는다(그 초안을 가리키는 클립을 먼저 잠그기 위해서다). */
	@Query("select d.id from ClipDraft d where d.member.id = :memberId")
	List<Long> findIdsByMemberId(Long memberId);

	/** 방송 삭제: 그 방송의 초안을 잠가 읽는다(클립 잠금 다음 자리). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from ClipDraft d where d.broadcast.id = :broadcastId")
	List<ClipDraft> findByBroadcastIdForUpdate(Long broadcastId);

	/** 처리기의 READY · FAILED 저장, 보관 정리 직전에 잠가 읽는다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from ClipDraft d where d.id = :id")
	Optional<ClipDraft> findByIdForUpdate(Long id);

	/** 처리기가 시작할 때 읽는 값: 창 · 방송 시작 시각 · 채널 · 방송 삭제 여부. */
	@Query("select d from ClipDraft d join fetch d.broadcast join fetch d.channel where d.id = :id")
	Optional<ClipDraft> findForProcessing(Long id);

	/** 받기 대기 초안 id (재시작 뒤 다시 줄 세우기). */
	@Query("""
			select d.id from ClipDraft d
			where d.status = com.livestream.livestream.clip.ClipDraftStatus.PENDING
			order by d.id
			""")
	List<Long> findPendingIds();

	/** 갇힌 초안 후보: PENDING 이고 기준 시각 전에 만든 것. */
	@Query("""
			select d.id from ClipDraft d
			where d.status = com.livestream.livestream.clip.ClipDraftStatus.PENDING and d.createdAt < :before
			order by d.id
			""")
	List<Long> findPendingCreatedBefore(Instant before);

	/** 보관 정리 후보: 기준 시각 전에 만든 초안 가운데 처리 중(PROCESSING · 삭제 안 됨) 클립이 가리키지 않는 것. */
	@Query("""
			select d.id from ClipDraft d
			where d.createdAt < :before
			  and not exists (
			    select 1 from Clip c where c.draft = d and c.deletedAt is null
			      and c.status = com.livestream.livestream.clip.ClipStatus.PROCESSING)
			order by d.id
			""")
	List<Long> findStale(Instant before);
}
