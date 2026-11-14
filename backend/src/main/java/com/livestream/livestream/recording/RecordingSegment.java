package com.livestream.livestream.recording;

import com.livestream.livestream.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 녹화 조각 하나 (DESIGN §4.3 recording_segment · §4.10). MediaMTX 가 조각 파일을 닫을 때(송출 끊김 · 6시간) 훅으로 받는다.
 * 재연결이 있으면 한 방송에 조각이 여러 개 생기고, 병합은 sequence 순서로 이어 붙인다.
 */
@Getter
@Entity
@Table(name = "recording_segment",
		indexes = @Index(name = "idx_recording_segment_recording", columnList = "recording_id, sequence"),
		uniqueConstraints = @UniqueConstraint(name = "uk_recording_segment_file", columnNames = {"recording_id", "file_path"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecordingSegment extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recording_id", nullable = false)
	private Recording recording;

	/** 녹화 디렉터리(app.recording.dir) 기준 상대 경로 {@code raw/live/<channelId>/<파일>} */
	@Column(name = "file_path", nullable = false, length = 255)
	private String filePath;

	/** 조각 도착 순서(1부터) */
	@Column(name = "sequence", nullable = false)
	private int sequence;

	public static RecordingSegment of(Recording recording, String filePath, int sequence) {
		RecordingSegment s = new RecordingSegment();
		s.recording = recording;
		s.filePath = filePath;
		s.sequence = sequence;
		return s;
	}
}
