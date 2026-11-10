package com.livestream.livestream.recording;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
			  and r.status = com.livestream.livestream.recording.RecordingStatus.READY
			  and r.visibility = com.livestream.livestream.recording.Visibility.PUBLIC
			order by b.startedAt desc, b.id desc
			""")
	List<Recording> findRecentPublic(Long excludeBroadcastId, Pageable limit);
}
