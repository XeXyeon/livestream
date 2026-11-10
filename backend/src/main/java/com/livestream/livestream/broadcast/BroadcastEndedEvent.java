package com.livestream.livestream.broadcast;

/**
 * 방송 종료가 커밋된 뒤 발행되는 애플리케이션 이벤트 (DESIGN §4.6 "end 공통").
 * 녹화 병합 시작(REC-1 {@code RecordingMergeService.enqueue}) · 채팅 버퍼 정리(CHAT-1)가 {@code @EventListener} 로 받는다.
 *
 * @param recordingId 방송의 recording id (없으면 null)
 */
public record BroadcastEndedEvent(Long broadcastId, Long channelId, Long recordingId) {
}
