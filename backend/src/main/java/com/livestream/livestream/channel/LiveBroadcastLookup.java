package com.livestream.livestream.channel;

import com.livestream.livestream.channel.dto.MyChannelResponse;
import java.util.Optional;

/**
 * 채널의 진행 중(LIVE · DISCONNECTED, 삭제 제외) 방송 조회. 채널 응답의 liveBroadcastId · currentBroadcast 에 쓴다.
 * 방송 도메인(BC-1)이 구현한다 — channel 이 broadcast 에 의존하지 않게 하려는 것({@link LiveBroadcastTerminator} 와 같은 이유).
 */
public interface LiveBroadcastLookup {

	Optional<MyChannelResponse.CurrentBroadcast> findOngoingOf(Long channelId);
}
