package com.livestream.livestream.channel;

/**
 * 스트림 키 재발급 전에 그 채널의 진행 중(LIVE · DISCONNECTED) 방송을 종료한다(DESIGN §4.16: "라이브 중이면 end 후 재발급").
 * 방송 도메인(BC-1)이 구현 빈을 등록한다. 구현이 없으면 아무 일도 하지 않는다.
 * 구현은 방송이 없으면 그냥 돌아와야 하고, MediaMTX kick 실패는 로그만 남겨 재발급을 막지 않아야 한다.
 */
public interface LiveBroadcastTerminator {

	void endLiveBroadcastOf(Long channelId);
}
