package com.livestream.livestream.broadcast;

import com.livestream.livestream.channel.Category;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.common.entity.BaseEntity;
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
 * 방송 한 회 (DESIGN §4.3 broadcast). MediaMTX ready 훅이 만들고 상태 전이는 {@link BroadcastLifecycleService} 만 한다.
 * soft delete(deleted_at) — 모든 조회는 deleted_at IS NULL.
 */
@Getter
@Entity
@Table(name = "broadcast", indexes = {
		@Index(name = "idx_broadcast_channel_status", columnList = "channel_id, status"),
		@Index(name = "idx_broadcast_status_started", columnList = "status, started_at")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Broadcast extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "channel_id", nullable = false)
	private Channel channel;

	@Column(name = "title", nullable = false, length = 100)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "category", nullable = false, length = 20)
	private Category category;

	@Column(name = "thumbnail_path", length = 255)
	private String thumbnailPath;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private BroadcastStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, length = 20)
	private BroadcastSource source;

	@Column(name = "started_at", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant startedAt;

	@Column(name = "disconnected_at", columnDefinition = "DATETIME(6)")
	private Instant disconnectedAt;

	@Column(name = "ended_at", columnDefinition = "DATETIME(6)")
	private Instant endedAt;

	@Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
	private Instant deletedAt;

	/**
	 * 이미 끝난 방송을 바로 만든다. 시연 시드(OPS-4) 전용: 상태 전이 · 종료 이벤트를 거치지 않는다.
	 * 제목 · 카테고리는 {@link #start} 와 같이 채널에서 복사한다.
	 */
	public static Broadcast endedForSeed(Channel channel, BroadcastSource source, Instant startedAt, Instant endedAt) {
		Broadcast b = start(channel, source, startedAt);
		b.end(endedAt);
		return b;
	}

	/** ready 훅으로 새 방송 시작: 제목 · 카테고리는 채널의 "다음 방송 정보"에서 복사한다. */
	public static Broadcast start(Channel channel, BroadcastSource source, Instant now) {
		Broadcast b = new Broadcast();
		b.channel = channel;
		b.title = channel.getTitle();
		b.category = channel.getCategory();
		b.status = BroadcastStatus.LIVE;
		b.source = source;
		b.startedAt = now;
		return b;
	}

	/** 유예 중 재연결 · overridePublisher 교체: LIVE로 되돌리고 송출 수단을 이번 값으로 바꾼다. */
	void resume(BroadcastSource source) {
		this.status = BroadcastStatus.LIVE;
		this.disconnectedAt = null;
		this.source = source;
	}

	void disconnect(Instant now) {
		this.status = BroadcastStatus.DISCONNECTED;
		this.disconnectedAt = now;
	}

	void end(Instant now) {
		this.status = BroadcastStatus.ENDED;
		this.endedAt = now;
	}

	/** PATCH: null 인 인자는 바꾸지 않는다. 검증 · 상태 규칙은 서비스가 끝낸 뒤 넘긴다. */
	void updateInfo(String title, Category category) {
		if (title != null) {
			this.title = title;
		}
		if (category != null) {
			this.category = category;
		}
	}

	void markDeleted(Instant now) {
		this.deletedAt = now;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}
}
