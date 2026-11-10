package com.livestream.livestream.recording;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingSegmentRepository extends JpaRepository<RecordingSegment, Long> {

	List<RecordingSegment> findByRecordingIdOrderBySequenceAsc(Long recordingId);

	long countByRecordingId(Long recordingId);

	boolean existsByRecordingIdAndFilePath(Long recordingId, String filePath);
}
