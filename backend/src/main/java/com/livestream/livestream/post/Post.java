package com.livestream.livestream.post;

import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 채널 커뮤니티 게시글 (DESIGN §4.3 post · §4.12). 채널 주인만 작성 · 수정 · 삭제(물리 삭제, 댓글은 FK cascade).
 * 본문은 텍스트만, 최대 5,000자.
 */
@Getter
@Entity
@Table(name = "post", indexes = @Index(name = "idx_post_channel", columnList = "channel_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Post extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "channel_id", nullable = false)
	private Channel channel;

	@Column(name = "title", nullable = false, length = 100)
	private String title;

	@Column(name = "content", nullable = false, columnDefinition = "TEXT")
	private String content;

	/** 정규화 · 길이 검증은 서비스가 끝낸 뒤 넘긴다. */
	public static Post write(Channel channel, String title, String content) {
		Post post = new Post();
		post.channel = channel;
		post.title = title;
		post.content = content;
		return post;
	}

	/** null 인 인자는 바꾸지 않는다(PATCH). */
	public void edit(String title, String content) {
		if (title != null) {
			this.title = title;
		}
		if (content != null) {
			this.content = content;
		}
	}
}
