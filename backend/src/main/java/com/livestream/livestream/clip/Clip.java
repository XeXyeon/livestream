package com.livestream.livestream.clip;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.common.entity.BaseEntity;
import com.livestream.livestream.member.Member;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.Visibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 클립 (DESIGN §4.3 clip · §4.18). 다시보기 구간이나 라이브 "최근 N초"를 잘라 만든 독립 파일.
 * PROCESSING 으로 만들고 처리기({@link ClipProcessingService})가 READY · FAILED 로 바꾼다. soft delete.
 */
@Getter
@Entity
@Table(name = "clip", indexes = {
		@Index(name = "idx_clip_channel_status", columnList = "channel_id, status"),
		@Index(name = "idx_clip_recording", columnList = "recording_id"),
		@Index(name = "idx_clip_creator", columnList = "creator_member_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Clip extends BaseEntity {

	static final int TITLE_MAX = 100;
	private static final int FAILURE_REASON_MAX = 200;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recording_id", nullable = false)
	private Recording recording;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "broadcast_id", nullable = false)
	private Broadcast broadcast;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "channel_id", nullable = false)
	private Channel channel;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "creator_member_id", nullable = false)
	private Member creator;

	@Column(name = "title", nullable = false, length = TITLE_MAX)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, length = 20)
	private ClipSource source;

	/** 방송 시작(= 다시보기 0초) 기준 시작 위치(초). */
	@Column(name = "start_seconds", nullable = false)
	private int startSeconds;

	/** 요청 길이(초). READY 때 ffprobe 실제 길이로 갱신한다. */
	@Column(name = "duration_seconds", nullable = false)
	private int durationSeconds;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ClipStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "visibility", nullable = false, length = 10)
	private Visibility visibility;

	/** 영상 파일이 지금 있는 상대 경로(app.recording.dir 기준): PUBLIC {@code vod/clips/<uuid>.mp4}, PRIVATE {@code private/clips/<uuid>.mp4}. */
	@Column(name = "file_path", length = 255)
	private String filePath;

	@Column(name = "size_bytes")
	private Long sizeBytes;

	/** {@code thumbnails/clips/<clipId>.jpg}(app.uploads.dir 기준), 만들지 못했으면 null. */
	@Column(name = "thumbnail_path", length = 255)
	private String thumbnailPath;

	@Column(name = "failure_reason", length = 255)
	private String failureReason;

	@Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
	private Instant deletedAt;

	/** 처리 대기(PROCESSING)로 만든다. 제목 · 길이 검증은 호출 쪽(ClipService)이 한다. */
	public static Clip create(Recording recording, Broadcast broadcast, Member creator, String title, ClipSource source,
			int startSeconds, int durationSeconds, Visibility visibility) {
		Clip c = new Clip();
		c.recording = recording;
		c.broadcast = broadcast;
		c.channel = broadcast.getChannel();
		c.creator = creator;
		c.title = title;
		c.source = source;
		c.startSeconds = startSeconds;
		c.durationSeconds = durationSeconds;
		c.status = ClipStatus.PROCESSING;
		c.visibility = visibility;
		return c;
	}

	/** 처리 성공. 호출 전에 잠금 읽기로 {@link #isProcessable()} 을 확인한다. */
	void markReady(String filePath, Integer durationSeconds, long sizeBytes, String thumbnailPath) {
		this.status = ClipStatus.READY;
		this.filePath = filePath;
		if (durationSeconds != null) {
			this.durationSeconds = durationSeconds;
		}
		this.sizeBytes = sizeBytes;
		this.thumbnailPath = thumbnailPath;
		this.failureReason = null;
	}

	/** 처리 실패. 사유는 앞 200자만 남긴다. */
	void markFailed(String reason) {
		this.status = ClipStatus.FAILED;
		this.failureReason = reason == null ? null
				: reason.codePointCount(0, reason.length()) <= FAILURE_REASON_MAX ? reason
				: reason.substring(0, reason.offsetByCodePoints(0, FAILURE_REASON_MAX));
	}

	void rename(String title) {
		this.title = title;
	}

	/** 공개 설정과 (READY 면 옮긴 뒤의) 파일 경로. */
	void changeVisibility(Visibility visibility, String filePath) {
		this.visibility = visibility;
		this.filePath = filePath;
	}

	void markDeleted(Instant now) {
		this.deletedAt = now;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	/** 처리기가 이어서 해도 되는 상태: PROCESSING 이고 삭제되지 않음. */
	boolean isProcessable() {
		return status == ClipStatus.PROCESSING && deletedAt == null;
	}

	/** 제목 · 공개 설정 · 삭제 · 비공개 조회 권한(§4.8): 만든 회원 또는 채널 주인. */
	public boolean isManageableBy(Long memberId) {
		return memberId != null && (isCreatedBy(memberId) || isChannelOwnedBy(memberId));
	}

	public boolean isCreatedBy(Long memberId) {
		return memberId != null && creator.getId().equals(memberId);
	}

	public boolean isChannelOwnedBy(Long memberId) {
		return memberId != null && channel.getMember().getId().equals(memberId);
	}
}
