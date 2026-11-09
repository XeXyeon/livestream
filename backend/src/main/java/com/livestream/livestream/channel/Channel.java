package com.livestream.livestream.channel;

import com.livestream.livestream.common.entity.BaseEntity;
import com.livestream.livestream.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 채널. AUTH-1에서는 가입 시 자동 생성만 한다. 조회 · 수정 · 키 재발급은 CH-1이 확장한다. */
@Getter
@Entity
@Table(name = "channel", uniqueConstraints = {
		@UniqueConstraint(name = "uk_channel_member", columnNames = "member_id"),
		@UniqueConstraint(name = "uk_channel_stream_key", columnNames = "stream_key"),
		@UniqueConstraint(name = "uk_channel_widget_key", columnNames = "widget_key")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Channel extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(name = "name", nullable = false, length = 30)
	private String name;

	@Column(name = "description", length = 500)
	private String description;

	@Column(name = "stream_key", nullable = false, columnDefinition = "CHAR(32)")
	private String streamKey;

	@Column(name = "widget_key", nullable = false, columnDefinition = "CHAR(32)")
	private String widgetKey;

	@Column(name = "auto_publish_recording", nullable = false)
	private boolean autoPublishRecording;

	@Column(name = "title", nullable = false, length = 100)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "category", nullable = false, length = 20)
	private Category category;

	/** 가입 시 자동 생성: name = nickname, title = "{nickname}의 방송", category = ETC. */
	public static Channel createFor(Member member) {
		Channel channel = new Channel();
		channel.member = member;
		channel.name = member.getNickname();
		channel.title = member.getNickname() + "의 방송";
		channel.category = Category.ETC;
		channel.streamKey = ChannelKeys.generate();
		channel.widgetKey = ChannelKeys.generate();
		channel.autoPublishRecording = true;
		return channel;
	}
}
