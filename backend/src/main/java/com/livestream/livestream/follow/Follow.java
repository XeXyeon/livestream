package com.livestream.livestream.follow;

import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.common.entity.BaseEntity;
import com.livestream.livestream.member.Member;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 채널 팔로우 (DESIGN §4.3 follow). (회원, 채널) 쌍은 유일하다. 자기 채널 팔로우 금지는 DB 가 아니라 {@link FollowService} 가 검사한다.
 * 회원 · 채널 FK 는 on delete cascade. 팔로우한 시각은 {@code created_at}.
 */
@Getter
@Entity
@Table(name = "follow",
		uniqueConstraints = @UniqueConstraint(name = "uk_follow_member_channel", columnNames = { "member_id", "channel_id" }),
		indexes = @Index(name = "idx_follow_channel", columnList = "channel_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Follow extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Member member;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "channel_id", nullable = false)
	@OnDelete(action = OnDeleteAction.CASCADE)
	private Channel channel;

	public static Follow of(Member member, Channel channel) {
		Follow follow = new Follow();
		follow.member = member;
		follow.channel = channel;
		return follow;
	}
}
