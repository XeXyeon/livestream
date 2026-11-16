package com.livestream.livestream.clip;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ClipRepository extends JpaRepository<Clip, Long> {

	/** 상세 · 권한 확인용: 채널 · 주인 · 만든 회원 · 방송 · 다시보기까지 함께 읽는다. 삭제된 클립은 없음. */
	@Query("""
			select c from Clip c join fetch c.channel ch join fetch ch.member join fetch c.creator
			  join fetch c.broadcast join fetch c.recording
			where c.id = :id and c.deletedAt is null
			""")
	Optional<Clip> findActive(Long id);

	/**
	 * 처리기의 READY · FAILED 저장, 수정 · 삭제 직전에 잠가 읽는다. 잠금 순서는 channel → broadcast → recording → clip 의 마지막 자리라
	 * 단독으로 잠가도 순서와 어긋나지 않는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Clip c where c.id = :id")
	Optional<Clip> findByIdForUpdate(Long id);

	/**
	 * 수정 · 삭제의 권한 확인: 엔티티를 올리지 않고 만든 회원 id 와 채널 주인 id 만 읽는다(첫 엔티티 읽기를 잠금 읽기로 하기 위해서다,
	 * RecordingRepository.findOwnerMemberIdOfActive 와 같은 이유).
	 */
	@Query("""
			select new com.livestream.livestream.clip.ClipManagers(c.creator.id, c.channel.member.id)
			from Clip c where c.id = :id and c.deletedAt is null
			""")
	Optional<ClipManagers> findManagersOfActive(Long id);

	/** 회원당 동시 처리 1개(§4.18). 삭제된 클립은 세지 않는다. */
	@Query("""
			select count(c) > 0 from Clip c
			where c.creator.id = :creatorId and c.status = :status and c.deletedAt is null
			""")
	boolean existsByCreatorIdAndStatus(Long creatorId, ClipStatus status);

	/** 방송 삭제: 그 방송의 남은 클립을 잠가 읽는다(recording 잠금 다음). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Clip c where c.broadcast.id = :broadcastId and c.deletedAt is null")
	List<Clip> findActiveByBroadcastIdForUpdate(Long broadcastId);

	/** 채널의 READY 클립(주인이면 비공개 포함) 최신순. */
	@Query(value = """
			select c from Clip c join fetch c.channel join fetch c.creator
			where c.channel.id = :channelId and c.deletedAt is null
			  and c.status = com.livestream.livestream.clip.ClipStatus.READY
			  and (:includePrivate = true or c.visibility = com.livestream.livestream.recording.Visibility.PUBLIC)
			order by c.createdAt desc, c.id desc
			""", countQuery = """
			select count(c) from Clip c
			where c.channel.id = :channelId and c.deletedAt is null
			  and c.status = com.livestream.livestream.clip.ClipStatus.READY
			  and (:includePrivate = true or c.visibility = com.livestream.livestream.recording.Visibility.PUBLIC)
			""")
	Page<Clip> findReadyByChannel(Long channelId, boolean includePrivate, Pageable pageable);

	/** 다시보기의 READY 클립(채널 주인이면 비공개 포함) 최신순. */
	@Query(value = """
			select c from Clip c join fetch c.channel join fetch c.creator
			where c.recording.id = :recordingId and c.deletedAt is null
			  and c.status = com.livestream.livestream.clip.ClipStatus.READY
			  and (:includePrivate = true or c.visibility = com.livestream.livestream.recording.Visibility.PUBLIC)
			order by c.createdAt desc, c.id desc
			""", countQuery = """
			select count(c) from Clip c
			where c.recording.id = :recordingId and c.deletedAt is null
			  and c.status = com.livestream.livestream.clip.ClipStatus.READY
			  and (:includePrivate = true or c.visibility = com.livestream.livestream.recording.Visibility.PUBLIC)
			""")
	Page<Clip> findReadyByRecording(Long recordingId, boolean includePrivate, Pageable pageable);

	/** 내가 만든 클립 전부(PROCESSING · FAILED 포함, 삭제 제외) 최신순. */
	@Query(value = """
			select c from Clip c join fetch c.channel join fetch c.creator
			where c.creator.id = :creatorId and c.deletedAt is null
			order by c.createdAt desc, c.id desc
			""", countQuery = """
			select count(c) from Clip c where c.creator.id = :creatorId and c.deletedAt is null
			""")
	Page<Clip> findByCreator(Long creatorId, Pageable pageable);

	/** 처리 대기 클립 id (재시작 뒤 다시 줄 세우기). */
	@Query("""
			select c.id from Clip c
			where c.status = com.livestream.livestream.clip.ClipStatus.PROCESSING and c.deletedAt is null
			order by c.id
			""")
	List<Long> findProcessingIds();

	/** 갇힌 클립 후보: PROCESSING 이고 기준 시각 전에 만든 것(삭제 제외). */
	@Query("""
			select c.id from Clip c
			where c.status = com.livestream.livestream.clip.ClipStatus.PROCESSING and c.deletedAt is null
			and c.createdAt < :before
			order by c.id
			""")
	List<Long> findProcessingCreatedBefore(Instant before);

	/** 처리기가 시작할 때 읽는 값: 원천 · 구간 · 다시보기 파일 · 방송 시작 시각 · 채널. */
	@Query("""
			select c from Clip c join fetch c.recording join fetch c.broadcast join fetch c.channel
			where c.id = :id
			""")
	Optional<Clip> findForProcessing(Long id);
}
