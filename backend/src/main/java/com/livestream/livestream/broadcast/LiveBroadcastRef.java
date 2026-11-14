package com.livestream.livestream.broadcast;

/** 진행 중 방송의 (방송 id, 채널 id) 쌍. 채널 엔티티를 읽지 않고 스케줄러가 쓰는 가벼운 조회 결과. */
public record LiveBroadcastRef(Long broadcastId, Long channelId) {
}
