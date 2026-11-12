package com.livestream.livestream.comment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CommentRepository extends JpaRepository<Comment, Long> {

	/** 목록에 보이는 댓글: 삭제 안 된 것 + 삭제됐지만 삭제 안 된 대댓글이 있는 것("삭제된 댓글입니다"). */
	String VISIBLE = "(c.deletedAt is null or exists (select r.id from Comment r where r.parent = c and r.deletedAt is null))";

	@Query(value = "select c from Comment c join fetch c.member where c.recording.id = :recordingId and " + VISIBLE,
			countQuery = "select count(c) from Comment c where c.recording.id = :recordingId and " + VISIBLE)
	Page<Comment> findVisibleByRecording(Long recordingId, Pageable pageable);

	@Query(value = "select c from Comment c join fetch c.member where c.post.id = :postId and " + VISIBLE,
			countQuery = "select count(c) from Comment c where c.post.id = :postId and " + VISIBLE)
	Page<Comment> findVisibleByPost(Long postId, Pageable pageable);

	/** 수정 · 삭제 · 답글 대상: 삭제된 댓글은 없음으로 본다. */
	@Query("select c from Comment c join fetch c.member where c.id = :id and c.deletedAt is null")
	Optional<Comment> findActive(Long id);

	/** 다시보기별 댓글 수(삭제 안 된 것). 결과 행은 {recordingId, count}. */
	@Query("""
			select c.recording.id, count(c) from Comment c
			where c.recording.id in :recordingIds and c.deletedAt is null group by c.recording.id
			""")
	List<Object[]> countByRecordingIds(Collection<Long> recordingIds);

	@Query("""
			select c.post.id, count(c) from Comment c
			where c.post.id in :postIds and c.deletedAt is null group by c.post.id
			""")
	List<Object[]> countByPostIds(Collection<Long> postIds);

	@Modifying
	@Query("delete from Comment c where c.recording.id = :recordingId and c.parent is not null")
	int deleteRepliesOfRecording(Long recordingId);

	@Modifying
	@Query("delete from Comment c where c.recording.id = :recordingId")
	int deleteRootsOfRecording(Long recordingId);

	/** 방송 삭제(§4.6): 다시보기 행은 남으므로(FILE_DELETED) FK cascade 가 돌지 않는다 → 대댓글부터 물리 삭제한다. */
	default int deleteAllOfRecording(Long recordingId) {
		return deleteRepliesOfRecording(recordingId) + deleteRootsOfRecording(recordingId);
	}
}
