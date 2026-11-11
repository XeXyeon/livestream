package com.livestream.livestream.comment;

import com.livestream.livestream.common.entity.BaseEntity;
import com.livestream.livestream.member.Member;
import com.livestream.livestream.post.Post;
import com.livestream.livestream.recording.Recording;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 댓글 (DESIGN §4.3 comment · §4.12). 대상은 다시보기({@code recording_id}) 또는 게시글({@code post_id}) 중 하나,
 * 대댓글은 1단계({@code parent_id}). 삭제는 {@code deleted_at}.
 * <p>
 * 대상 FK 는 on delete cascade(게시글 물리 삭제 시 댓글도). 대댓글 FK 도 cascade 라 부모와 함께 지워진다.
 * "대상 둘 중 하나" check 제약은 DB 에 두지 않는다: MySQL 은 on delete cascade 가 걸린 컬럼을 CHECK 에 쓰지 못한다
 * (ERROR 3823). 대신 생성 메서드 두 개({@link #onRecording} · {@link #onPost})로만 만든다.
 */
@Getter
@Entity
@Table(name = "comment", indexes = {
		@Index(name = "idx_comment_recording", columnList = "recording_id"),
		@Index(name = "idx_comment_post", columnList = "post_id")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "recording_id")
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Recording recording;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "post_id")
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Post post;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_id")
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Comment parent;

	@Column(name = "content", nullable = false, length = 500)
	private String content;

	@Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
	private Instant deletedAt;

	/** 정규화 · 길이 · parent 검증은 서비스가 끝낸 뒤 넘긴다. */
	public static Comment onRecording(Member member, Recording recording, Comment parent, String content) {
		Comment c = of(member, parent, content);
		c.recording = Objects.requireNonNull(recording);
		return c;
	}

	public static Comment onPost(Member member, Post post, Comment parent, String content) {
		Comment c = of(member, parent, content);
		c.post = Objects.requireNonNull(post);
		return c;
	}

	private static Comment of(Member member, Comment parent, String content) {
		Comment c = new Comment();
		c.member = Objects.requireNonNull(member);
		c.parent = parent;
		c.content = Objects.requireNonNull(content);
		return c;
	}

	public void edit(String content) {
		this.content = Objects.requireNonNull(content);
	}

	public void markDeleted(Instant now) {
		this.deletedAt = now;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public boolean isWrittenBy(Long memberId) {
		return member.getId().equals(memberId);
	}
}
