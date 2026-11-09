package com.livestream.livestream.channel.dto;

import com.livestream.livestream.channel.Category;
import io.swagger.v3.oas.annotations.media.Schema;

/** 길이 · 공백 검증은 strip 이후 기준이라 서비스가 한다. PATCH 의미: 필드가 없거나 null 이면 바꾸지 않는다. description 은 빈 문자열로 지울 수 있다. */
@Schema(description = "채널 정보 수정 (보낸 필드만 반영)")
public record ChannelUpdateRequest(
		@Schema(description = "채널 이름 1~30자(공백만 불가)") String name,
		@Schema(description = "채널 소개 500자 이하. 빈 문자열이면 지움") String description,
		@Schema(description = "다음 방송 제목 1~100자(공백만 불가)") String title,
		@Schema(description = "다음 방송 카테고리 코드", example = "GAME") Category category,
		@Schema(description = "방송 종료 뒤 다시보기 자동 공개") Boolean autoPublishRecording) {
}
