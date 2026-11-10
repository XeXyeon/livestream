package com.livestream.livestream.broadcast;

import com.livestream.livestream.channel.LiveBroadcastLookup;
import com.livestream.livestream.channel.dto.MyChannelResponse;
import com.livestream.livestream.common.security.MemberPrincipal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 방송 조회 · 소유자 API. 상태 전이 자체는 {@link BroadcastLifecycleService} 가 한다. */
@Service
@RequiredArgsConstructor
public class BroadcastService implements LiveBroadcastLookup {

	private final BroadcastRepository broadcastRepository;
	private final BroadcastLifecycleService lifecycle;

	/** POST /broadcasts/{id}/end — 소유자만. */
	public void end(MemberPrincipal me, Long broadcastId) {
		lifecycle.endByOwner(broadcastId, me);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MyChannelResponse.CurrentBroadcast> findOngoingOf(Long channelId) {
		return broadcastRepository.findOngoing(channelId)
				.map(b -> new MyChannelResponse.CurrentBroadcast(b.getId(), b.getStatus().name()));
	}
}
