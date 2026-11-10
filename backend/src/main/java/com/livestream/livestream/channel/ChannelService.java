package com.livestream.livestream.channel;

import com.livestream.livestream.channel.dto.CategoryResponse;
import com.livestream.livestream.channel.dto.ChannelResponse;
import com.livestream.livestream.channel.dto.ChannelUpdateRequest;
import com.livestream.livestream.channel.dto.MyChannelResponse;
import com.livestream.livestream.channel.dto.StreamKeyResponse;
import com.livestream.livestream.channel.dto.WidgetKeyResponse;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.common.security.OwnershipGuard;
import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 채널 조회 · 정보 수정 · 키 재발급 (DESIGN §4.16 `/channels/*`). */
@Service
@RequiredArgsConstructor
public class ChannelService {

	private static final int NAME_MAX = 30;
	private static final int TITLE_MAX = 100;
	private static final int DESCRIPTION_MAX = 500;
	/** 이름 · 제목에는 개행 등 제어문자를 허용하지 않는다(목록 UI · 로그 깨짐 방지). 길이는 문자(코드포인트) 단위다. */
	private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

	private final ChannelRepository channelRepository;
	private final AppProperties props;
	private final ObjectProvider<LiveBroadcastTerminator> liveBroadcastTerminators;
	private final LiveBroadcastLookup liveBroadcastLookup;

	@Transactional(readOnly = true)
	public ChannelResponse getPublic(Long channelId) {
		Channel channel = channelRepository.findById(channelId)
				.orElseThrow(() -> new ApiException(ErrorCode.CHANNEL_NOT_FOUND));
		Long liveBroadcastId = currentBroadcast(channel).map(MyChannelResponse.CurrentBroadcast::id).orElse(null);
		return new ChannelResponse(channel.getId(), channel.getName(), channel.getDescription(),
				CategoryResponse.of(channel.getCategory()), liveBroadcastId, recordingCount(channel),
				postCount(channel), new ChannelResponse.Owner(channel.getMember().getNickname()));
	}

	@Transactional(readOnly = true)
	public MyChannelResponse getMine(MemberPrincipal me) {
		return toMine(findMine(me, false));
	}

	@Transactional
	public MyChannelResponse updateMine(MemberPrincipal me, ChannelUpdateRequest request) {
		Channel channel = findMine(me, true);
		String name = request.name() == null ? null : requireText(request.name(), NAME_MAX, "채널 이름");
		String title = request.title() == null ? null : requireText(request.title(), TITLE_MAX, "방송 제목");
		String description = request.description() == null ? null : request.description().strip();
		if (description != null && description.codePointCount(0, description.length()) > DESCRIPTION_MAX) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "채널 소개는 " + DESCRIPTION_MAX + "자 이하여야 합니다");
		}
		channel.updateInfo(name, description, title, request.category(), request.autoPublishRecording());
		return toMine(channel);
	}

	/** 라이브 중이면 먼저 방송을 종료(BC-1 훅: end · 커밋 뒤 kick)한 뒤 새 키를 발급한다. 옛 키는 즉시 무효다. */
	@Transactional
	public StreamKeyResponse rotateStreamKey(MemberPrincipal me) {
		Channel channel = findMine(me, true);
		liveBroadcastTerminators.orderedStream().forEach(t -> t.endLiveBroadcastOf(channel.getId()));
		return new StreamKeyResponse(channel.rotateStreamKey());
	}

	@Transactional
	public WidgetKeyResponse rotateWidgetKey(MemberPrincipal me) {
		Channel channel = findMine(me, true);
		String widgetKey = channel.rotateWidgetKey();
		return new WidgetKeyResponse(widgetKey, widgetUrl(widgetKey));
	}

	/** 로그인한 회원의 채널. 채널 id를 입력으로 받지 않으므로 남의 채널에 닿을 수 없고, 헬퍼로 한 번 더 확인한다. */
	private Channel findMine(MemberPrincipal me, boolean forUpdate) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		Channel channel = (forUpdate ? channelRepository.findByMemberIdForUpdate(me.id())
				: channelRepository.findByMemberId(me.id()))
				.orElseThrow(() -> new ApiException(ErrorCode.CHANNEL_NOT_FOUND));
		OwnershipGuard.requireChannelOwner(channel, me);
		return channel;
	}

	private MyChannelResponse toMine(Channel channel) {
		Long id = channel.getId();
		String whipUrl = baseUrl() + "/live/" + id + "/whip";
		MyChannelResponse.CurrentBroadcast current = currentBroadcast(channel).orElse(null);
		return new MyChannelResponse(id, channel.getName(), channel.getDescription(),
				CategoryResponse.of(channel.getCategory()), current == null ? null : current.id(), recordingCount(channel),
				postCount(channel), new ChannelResponse.Owner(channel.getMember().getNickname()),
				channel.getTitle(), channel.getStreamKey(), channel.getWidgetKey(), channel.isAutoPublishRecording(),
				current,
				new MyChannelResponse.Publish(whipUrl, whipUrl + "?src=obs", channel.getStreamKey(),
						"rtmp://" + rtmpHost() + "/live", id + "?user=obs&pass=" + channel.getStreamKey()),
				widgetUrl(channel.getWidgetKey()));
	}

	// ---- 다른 도메인이 아직 없는 값. 해당 항목이 이 메서드들을 채운다. ----

	/** 진행 중(LIVE · DISCONNECTED, 삭제 제외) 방송 {id, status}. liveBroadcastId 도 이 방송의 id다. */
	private Optional<MyChannelResponse.CurrentBroadcast> currentBroadcast(Channel channel) {
		return liveBroadcastLookup.findOngoingOf(channel.getId());
	}

	/** TODO(REC-2): READY · PUBLIC 다시보기 수. */
	private long recordingCount(Channel channel) {
		return 0;
	}

	/** TODO(POST-1): 커뮤니티 글 수. */
	private long postCount(Channel channel) {
		return 0;
	}

	// ---- 안내 문자열 ----

	private String baseUrl() {
		String url = props.publicUrl();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** rtmp://{host}/live — public-url 의 호스트만 쓴다(포트 · 경로 제외). */
	private String rtmpHost() {
		String host = URI.create(baseUrl()).getHost();
		return host != null ? host : "localhost";
	}

	private String widgetUrl(String widgetKey) {
		return baseUrl() + "/widget/" + widgetKey;
	}

	private static String requireText(String raw, int max, String label) {
		String value = raw.strip();
		if (value.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, label + "을(를) 입력해 주세요");
		}
		if (CONTROL_CHARS.matcher(value).find()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, label + "에 사용할 수 없는 문자가 있습니다");
		}
		if (value.codePointCount(0, value.length()) > max) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, label + "은(는) " + max + "자 이하여야 합니다");
		}
		return value;
	}
}
