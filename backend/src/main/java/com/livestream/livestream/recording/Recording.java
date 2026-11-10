package com.livestream.livestream.recording;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방송 한 회의 다시보기 (DESIGN §4.3 recording). 방송 시작 때 RECORDING으로 만들고(BC-1), 종료 때 PROCESSING으로 바꾼다.
 * 병합 · READY · 보관은 REC-1.
 */
@Getter
@Entity
@Table(name = "recording", uniqueConstraints = @UniqueConstraint(name = "uk_recording_broadcast", columnNames = "broadcast_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recording extends BaseEntity {

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "broadcast_id", nullable = false)
	private Broadcast broadcast;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private RecordingStatus status;

	/** vod/&lt;uuid&gt;.mp4 상대 경로 */
	@Column(name = "file_path", length = 255)
	private String filePath;

	@Column(name = "duration_seconds")
	private Integer durationSeconds;

	@Column(name = "size_bytes")
	private Long sizeBytes;

	@Enumerated(EnumType.STRING)
	@Column(name = "visibility", nullable = false, length = 10)
	private Visibility visibility;

	@Column(name = "file_deleted_at", columnDefinition = "DATETIME(6)")
	private Instant fileDeletedAt;

	@Column(name = "failure_reason", length = 255)
	private String failureReason;

	/** 방송 시작과 함께 만든다. 공개 여부는 채널의 auto_publish_recording 을 따른다. */
	public static Recording startFor(Broadcast broadcast, boolean autoPublish) {
		Recording r = new Recording();
		r.broadcast = broadcast;
		r.status = RecordingStatus.RECORDING;
		r.visibility = autoPublish ? Visibility.PUBLIC : Visibility.PRIVATE;
		return r;
	}

	/** 방송 종료: 녹화 중이었으면 병합 대기(PROCESSING)로. 그 외 상태는 그대로 둔다. */
	public void markProcessing() {
		if (status == RecordingStatus.RECORDING) {
			status = RecordingStatus.PROCESSING;
		}
	}
}
