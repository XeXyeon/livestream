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

/** 채널. 가입 시 자동 생성(AUTH-1), 정보 수정 · 키 재발급(CH-1). */
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

	/** null 인 인자는 바꾸지 않는다(PATCH). description 은 빈 문자열이면 지운다. 정규화 · 검증은 서비스가 끝낸 뒤 넘긴다. */
	public void updateInfo(String name, String description, String title, Category category,
			Boolean autoPublishRecording) {
		if (name != null) {
			this.name = name;
		}
		if (description != null) {
			this.description = description.isEmpty() ? null : description;
		}
		if (title != null) {
			this.title = title;
		}
		if (category != null) {
			this.category = category;
		}
		if (autoPublishRecording != null) {
			this.autoPublishRecording = autoPublishRecording;
		}
	}

	public String rotateStreamKey() {
		this.streamKey = ChannelKeys.generate();
		return this.streamKey;
	}

	public String rotateWidgetKey() {
		this.widgetKey = ChannelKeys.generate();
		return this.widgetKey;
	}
}
