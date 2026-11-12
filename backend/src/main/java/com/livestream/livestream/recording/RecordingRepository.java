package com.livestream.livestream.recording;

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

public interface RecordingRepository extends JpaRepository<Recording, Long> {

	Optional<Recording> findByBroadcastId(Long broadcastId);

	List<Recording> findByBroadcastIdIn(Collection<Long> broadcastIds);

	/**
	 * 방송 삭제용 잠금 읽기: 병합이 READY · file_path 를 막 커밋했어도 최신 값을 보고, 삭제의 FILE_DELETED 갱신이
	 * 병합 결과를 덮어쓰지 않게 한다. 잠금 순서는 channel → broadcast → recording.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Recording r where r.broadcast.id = :broadcastId")
	Optional<Recording> findByBroadcastIdForUpdate(Long broadcastId);

	/** 채널의 공개 다시보기(방송 ENDED · READY · PUBLIC · 삭제 제외), 방송 시작 최신순. */
	@Query(value = """
			select r from Recording r join fetch r.broadcast b join fetch b.channel c
			where c.id = :channelId and b.deletedAt is null
			  and b.status = com.livestream.livestream.broadcast.BroadcastStatus.ENDED
			  and r.status = com.livestream.livestream.recording.RecordingStatus.READY
			  and r.visibility = com.livestream.livestream.recording.Visibility.PUBLIC
			order by b.startedAt desc, b.id desc
			""", countQuery = """
			select count(r) from Recording r join r.broadcast b
			where b.channel.id = :channelId and b.deletedAt is null
			  and b.status = com.livestream.livestream.broadcast.BroadcastStatus.ENDED
			  and r.status = com.livestream.livestream.recording.RecordingStatus.READY
			  and r.visibility = com.livestream.livestream.recording.Visibility.PUBLIC
			""")
	Page<Recording> findPublicByChannel(Long channelId, Pageable pageable);

	/** 최근 공개 다시보기(추천용). 지금 보고 있던 방송은 뺀다. */
	@Query("""
			select r from Recording r join fetch r.broadcast b join fetch b.channel
			where b.id <> :excludeBroadcastId and b.deletedAt is null
			  and b.status = com.livestream.livestream.broadcast.BroadcastStatus.ENDED
			  and r.status = com.livestream.livestream.recording.RecordingStatus.READY
			  and r.visibility = com.livestream.livestream.recording.Visibility.PUBLIC
			order by b.startedAt desc, b.id desc
			""")
	List<Recording> findRecentPublic(Long excludeBroadcastId, Pageable limit);

	// ---------- REC-2 다시보기 API ----------

	/** 공개 다시보기 전체(GET /recordings), 방송 시작 최신순. 조건은 {@link #findPublicByChannel} 과 같다. */
	@Query(value = """
			select r from Recording r join fetch r.broadcast b join fetch b.channel c
			where b.deletedAt is null
			  and b.status = com.livestream.livestream.broadcast.BroadcastStatus.ENDED
			  and r.status = com.livestream.livestream.recording.RecordingStatus.READY
			  and r.visibility = com.livestream.livestream.recording.Visibility.PUBLIC
			order by b.startedAt desc, b.id desc
			""", countQuery = """
			select count(r) from Recording r join r.broadcast b
			where b.deletedAt is null
			  and b.status = com.livestream.livestream.broadcast.BroadcastStatus.ENDED
			  and r.status = com.livestream.livestream.recording.RecordingStatus.READY
			  and r.visibility = com.livestream.livestream.recording.Visibility.PUBLIC
			""")
	Page<Recording> findPublic(Pageable pageable);

	/** 채널 페이지의 recordingCount: {@link #findPublicByChannel} 과 같은 조건의 수. */
	@Query("""
			select count(r) from Recording r join r.broadcast b
			where b.channel.id = :channelId and b.deletedAt is null
			  and b.status = com.livestream.livestream.broadcast.BroadcastStatus.ENDED
			  and r.status = com.livestream.livestream.recording.RecordingStatus.READY
			  and r.visibility = com.livestream.livestream.recording.Visibility.PUBLIC
			""")
	long countPublicByChannel(Long channelId);

	/** 상세 · 소유자 확인용: 방송 · 채널 · 주인까지 함께 읽는다. 삭제된 방송의 다시보기는 없음으로 본다. */
	@Query("""
			select r from Recording r join fetch r.broadcast b join fetch b.channel c join fetch c.member
			where r.id = :id and b.deletedAt is null
			""")
	Optional<Recording> findActive(Long id);

	/**
	 * 공개 설정만 바꾼다. 엔티티를 고쳐 flush 하면 모든 컬럼을 다시 쓰므로, 그 사이 병합이 커밋한 READY · file_path 를
	 * 오래된 값으로 덮어쓸 수 있다. 그래서 이 컬럼만 갱신하는 쿼리를 쓴다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Recording r set r.visibility = :visibility, r.updatedAt = :now where r.id = :id")
	int updateVisibility(Long id, Visibility visibility, Instant now);

	/** 공개 설정과 영상 파일 경로를 함께 바꾼다(READY 영상을 반대 폴더로 옮긴 뒤). 호출 전에 recording 을 잠근다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Recording r set r.visibility = :visibility, r.filePath = :filePath, r.updatedAt = :now where r.id = :id")
	int updateVisibilityAndFilePath(Long id, Visibility visibility, String filePath, Instant now);

	/** 파일을 다른 폴더에서 찾아 맞춘 뒤 file_path 를 고친다. 호출 전에 recording 을 잠근다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Recording r set r.filePath = :filePath where r.id = :id")
	int updateFilePath(Long id, String filePath);

	/** 공개 설정 변경의 주인 확인: 엔티티를 올리지 않고 채널 주인의 member id 만 읽는다. 삭제된 방송의 다시보기는 없음. */
	@Query("""
			select c.member.id from Recording r join r.broadcast b join b.channel c
			where r.id = :id and b.deletedAt is null
			""")
	Optional<Long> findOwnerMemberIdOfActive(Long id);

	// ---------- REC-1 녹화 병합 · 보관 ----------

	/** 댓글 작성: 공유 잠금(FOR SHARE) — 방송 삭제의 댓글 물리 삭제와 직렬화한다. 방송 · 채널은 잠그지 않는다(잠금 순서). */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select r from Recording r where r.id = :id")
	Optional<Recording> findByIdForShare(Long id);

	/** 병합 결과 저장 · 조각 추가 직전에 잠가 읽는다(방송 삭제의 FILE_DELETED 와 겹치지 않게). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Recording r where r.id = :id")
	Optional<Recording> findByIdForUpdate(Long id);

	/** 채널의 가장 최근 방송의 recording id (조각 훅이 붙일 곳). 삭제된 방송도 포함한다(늦게 온 조각 파일 정리용). */
	@Query("select r.id from Recording r join r.broadcast b where b.channel.id = :channelId order by b.id desc")
	List<Long> findLatestIdByChannel(Long channelId, Pageable limit);

	/** 보관 기간이 지난 READY 다시보기 id. */
	@Query("""
			select r.id from Recording r
			where r.status = com.livestream.livestream.recording.RecordingStatus.READY and r.createdAt < :before
			""")
	List<Long> findReadyCreatedBefore(Instant before);

	/** 병합 대기 중인 녹화 id (재시작 뒤 다시 줄 세우기). */
	@Query("select r.id from Recording r where r.status = com.livestream.livestream.recording.RecordingStatus.PROCESSING")
	List<Long> findProcessingIds();
}
