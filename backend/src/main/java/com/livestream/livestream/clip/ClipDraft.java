package com.livestream.livestream.clip;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.common.entity.BaseEntity;
import com.livestream.livestream.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 라이브 클립 초안 (DESIGN §4.3 clip_draft · §4.18 라이브 클립 초안, CLIP-3). 방송의 최근 {@code window_seconds}초를 재생 서버에서
 * 받아 둔 임시 파일이다. 만든 회원만 토큰으로 보고, 그 안에서 구간을 골라 클립을 만든다. soft delete 없음 — 보관 시간이 지나거나
 * 새 초안으로 대체되면 행과 파일을 지운다(클립의 {@code draft_id} 는 FK {@code ON DELETE SET NULL}).
 */
@Getter
@Entity
@Table(name = "clip_draft", indexes = {
		@Index(name = "idx_clip_draft_member", columnList = "member_id"),
		@Index(name = "idx_clip_draft_created", columnList = "created_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClipDraft extends BaseEntity {

	private static final int FAILURE_REASON_MAX = 200;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "broadcast_id", nullable = false)
	private Broadcast broadcast;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "channel_id", nullable = false)
	private Channel channel;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	/** 방송 시작 기준 창의 시작 위치(초). */
	@Column(name = "window_start_seconds", nullable = false)
	private int windowStartSeconds;

	/** 창 길이(초). READY 때 ffprobe 실제 길이(내림)로 갱신한다. */
	@Column(name = "window_seconds", nullable = false)
	private int windowSeconds;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ClipDraftStatus status;

	/** {@code private/clip-drafts/<uuid>.mp4}(app.recording.dir 기준). READY 전에는 null. */
	@Column(name = "file_path", length = 255)
	private String filePath;

	@Column(name = "failure_reason", length = 255)
	private String failureReason;

	/** 받기 대기(PENDING)로 만든다. 창 계산 · 검증은 호출 쪽(ClipDraftService)이 한다. */
	public static ClipDraft create(Broadcast broadcast, Member member, int windowStartSeconds, int windowSeconds) {
		ClipDraft d = new ClipDraft();
		d.broadcast = broadcast;
		d.channel = broadcast.getChannel();
		d.member = member;
		d.windowStartSeconds = windowStartSeconds;
		d.windowSeconds = windowSeconds;
		d.status = ClipDraftStatus.PENDING;
		return d;
	}

	/** 받기 성공. 호출 전에 잠금 읽기로 {@link #isPending()} 을 확인한다. */
	void markReady(String filePath, int windowSeconds) {
		this.status = ClipDraftStatus.READY;
		this.filePath = filePath;
		this.windowSeconds = windowSeconds;
		this.failureReason = null;
	}

	/** 받기 실패. 사유는 코드만(앞 200자). */
	void markFailed(String reason) {
		this.status = ClipDraftStatus.FAILED;
		this.failureReason = reason == null ? null
				: reason.length() <= FAILURE_REASON_MAX ? reason : reason.substring(0, FAILURE_REASON_MAX);
	}

	boolean isPending() {
		return status == ClipDraftStatus.PENDING;
	}

	public boolean isReady() {
		return status == ClipDraftStatus.READY && filePath != null;
	}
}
