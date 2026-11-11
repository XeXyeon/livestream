package com.livestream.livestream.integration;

/** 위젯 키가 바뀌었다: 그 채널의 SSE 연결을 모두 닫는다(옛 키로 연 위젯이 계속 받지 않게). */
public record WidgetKeyRotatedEvent(Long channelId) {
}
