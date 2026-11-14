package com.livestream.livestream.integration.dto;

/** SSE {@code event: ready} 의 data. */
public record WidgetReadyEvent(Long channelId, int version) {
}
