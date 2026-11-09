package com.livestream.livestream.channel.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** GET · PATCH /channels/me — 공개 정보 + 방송 설정 · 키 · 송출 안내. 본인에게만 내려간다. */
@Schema(description = "내 채널(키 · 송출 정보 포함)")
public record MyChannelResponse(
		Long id,
		String name,
		String description,
		CategoryResponse category,
		Long liveBroadcastId,
		long recordingCount,
		long postCount,
		ChannelResponse.Owner owner,
		@Schema(description = "다음 방송 제목") String title,
		String streamKey,
		String widgetKey,
		boolean autoPublishRecording,
		@Schema(description = "진행 중 방송, 없으면 null") CurrentBroadcast currentBroadcast,
		Publish publish,
		@Schema(description = "후원 알림 위젯 URL (OBS 브라우저 소스)") String widgetUrl) {

	public record CurrentBroadcast(Long id, String status) {
	}

	@Schema(description = "송출 안내 문자열")
	public record Publish(
			@Schema(description = "브라우저 WHIP. ?src=pc / ?src=mobile 을 붙여 쓴다") String whipUrl,
			@Schema(description = "OBS WHIP 서버 칸") String obsWhipUrl,
			@Schema(description = "OBS WHIP \"Bearer 토큰\" 칸에 넣을 값") String obsWhipBearer,
			@Schema(description = "OBS RTMP 서버 칸") String rtmpUrl,
			@Schema(description = "OBS RTMP 스트림 키 칸") String rtmpStreamKey) {
	}
}
