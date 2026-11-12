package com.livestream.livestream.comment;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 목록 · 상세의 commentCount(삭제 안 된 댓글 수, 대댓글 포함). 목록은 한 번의 group by 로 센다. */
@Component
@RequiredArgsConstructor
public class CommentCounter {

	private final CommentRepository commentRepository;

	public Map<Long, Long> ofRecordings(Collection<Long> recordingIds) {
		return recordingIds.isEmpty() ? Map.of() : toMap(commentRepository.countByRecordingIds(recordingIds));
	}

	public long ofRecording(Long recordingId) {
		return ofRecordings(List.of(recordingId)).getOrDefault(recordingId, 0L);
	}

	public Map<Long, Long> ofPosts(Collection<Long> postIds) {
		return postIds.isEmpty() ? Map.of() : toMap(commentRepository.countByPostIds(postIds));
	}

	public long ofPost(Long postId) {
		return ofPosts(List.of(postId)).getOrDefault(postId, 0L);
	}

	private static Map<Long, Long> toMap(List<Object[]> rows) {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : rows) {
			counts.put((Long) row[0], (Long) row[1]);
		}
		return counts;
	}
}
