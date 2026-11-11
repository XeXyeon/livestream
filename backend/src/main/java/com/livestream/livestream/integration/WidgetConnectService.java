package com.livestream.livestream.integration;

import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.donation.Donation;
import com.livestream.livestream.donation.DonationRepository;
import com.livestream.livestream.integration.dto.WidgetDonationEvent;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SSE 연결 준비(DB): 위젯 키 → 채널, Last-Event-ID 뒤의 승인 후원(최대 {@value #REPLAY_MAX}건). 연결 중에는 트랜잭션을 잡지 않는다. */
@Service
@RequiredArgsConstructor
public class WidgetConnectService {

	static final int REPLAY_MAX = 100;
	private static final Pattern WIDGET_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final Pattern EVENT_ID = Pattern.compile("[1-9]\\d{0,17}");

	private final ChannelRepository channelRepository;
	private final DonationRepository donationRepository;

	public record Connection(Long channelId, List<WidgetDonationEvent> replay) {
	}

	@Transactional(readOnly = true)
	public Connection prepare(String widgetKey, String lastEventId) {
		if (widgetKey == null || !WIDGET_KEY.matcher(widgetKey).matches()) {
			throw new ApiException(ErrorCode.CHANNEL_NOT_FOUND);
		}
		Channel channel = channelRepository.findByWidgetKey(widgetKey)
				.orElseThrow(() -> new ApiException(ErrorCode.CHANNEL_NOT_FOUND));
		return new Connection(channel.getId(), replay(channel.getId(), lastEventId));
	}

	/** 기준 id 가 이 채널의 승인 후원이 아니면(형식 오류 · 다른 채널) 재전송하지 않는다. */
	private List<WidgetDonationEvent> replay(Long channelId, String lastEventId) {
		if (lastEventId == null || !EVENT_ID.matcher(lastEventId.strip()).matches()) {
			return List.of();
		}
		Long afterId = Long.valueOf(lastEventId.strip());
		return donationRepository.findApprovedOfChannel(afterId, channelId)
				.map(base -> donationRepository.findApprovedAfter(channelId, base.getApprovedAt(), base.getId(),
						PageRequest.of(0, REPLAY_MAX)))
				.orElse(List.of())
				.stream().map(WidgetConnectService::toEvent).toList();
	}

	public static WidgetDonationEvent toEvent(Donation d) {
		return WidgetDonationEvent.of(d.getId(), d.getBroadcast().getId(), d.getDonor().getNickname(), d.getAmount(),
				d.getMessage(), d.getApprovedAt());
	}
}
