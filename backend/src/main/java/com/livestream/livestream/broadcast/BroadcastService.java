package com.livestream.livestream.broadcast;

import com.livestream.livestream.broadcast.dto.BroadcastDetail;
import com.livestream.livestream.broadcast.dto.BroadcastSummary;
import com.livestream.livestream.broadcast.dto.BroadcastUpdateRequest;
import com.livestream.livestream.broadcast.dto.MyBroadcastItem;
import com.livestream.livestream.broadcast.dto.Recommendations;
import com.livestream.livestream.broadcast.dto.RecordingRef;
import com.livestream.livestream.channel.Category;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.channel.LiveBroadcastLookup;
import com.livestream.livestream.channel.dto.CategoryResponse;
import com.livestream.livestream.channel.dto.MyChannelResponse;
import com.livestream.livestream.chat.ViewerCounter;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.common.security.OwnershipGuard;
import com.livestream.livestream.common.tx.AfterCommit;
import com.livestream.livestream.donation.DonationRepository;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.RecordingService;
import com.livestream.livestream.recording.dto.RecordingSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방송 조회 · 소유자 API (DESIGN §4.6 수정 · 삭제, §4.16 /broadcasts*). 상태 전이 자체는 {@link BroadcastLifecycleService} 가 한다.
 * 수정 · 삭제도 상태 전이와 같은 잠금 순서(channel → broadcast)를 따른다.
 */
@Service
@RequiredArgsConstructor
public class BroadcastService implements LiveBroadcastLookup {

	/** 시청자 수 내림차순 → 시작 시각 최신순 → id 내림차순(같은 시각일 때 순서 고정). */
	static final Comparator<BroadcastSummary> POPULAR_FIRST = Comparator
			.comparingLong(BroadcastSummary::viewerCount).reversed()
			.thenComparing(BroadcastSummary::startedAt, Comparator.reverseOrder())
			.thenComparing(BroadcastSummary::id, Comparator.reverseOrder());

	private static final int TITLE_MAX = 100;
	private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

	private final BroadcastRepository broadcastRepository;
	private final ChannelRepository channelRepository;
	private final RecordingRepository recordingRepository;
	private final RecordingService recordingService;
	private final BroadcastLifecycleService lifecycle;
	private final RecommendationService recommendationService;
	private final ViewerCounter viewerCounter;
	private final DonationRepository donationRepository;
	private final AppProperties props;

	// ---------- 조회 ----------

	/**
	 * GET /broadcasts — 진행 중 방송(LIVE · DISCONNECTED)만 받는다. 시청자 수는 메모리 값이라 DB가 아니라 여기서 정렬 · 자른다
	 * (진행 중 방송은 채널당 1건이라 수가 적다).
	 */
	@Transactional(readOnly = true)
	public PageResponse<BroadcastSummary> list(BroadcastStatus status, Category category, Integer page, Integer size) {
		BroadcastStatus s = status == null ? BroadcastStatus.LIVE : status;
		if (!s.isOngoing()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "status는 LIVE 또는 DISCONNECTED만 지원합니다(지난 방송은 다시보기 목록)");
		}
		List<BroadcastSummary> all = broadcastRepository.findAllByStatus(s, category).stream()
				.map(b -> BroadcastSummary.of(b, viewerCounter.count(b.getId())))
				.sorted(POPULAR_FIRST)
				.toList();
		Pageable pageable = PageResponse.pageable(page, size, Sort.unsorted());
		int from = (int) Math.min(pageable.getOffset(), all.size());
		int to = Math.min(from + pageable.getPageSize(), all.size());
		Page<BroadcastSummary> slice = new PageImpl<>(all.subList(from, to), pageable, all.size());
		return PageResponse.of(slice, Function.identity());
	}

	@Transactional(readOnly = true)
	public BroadcastDetail get(Long id, MemberPrincipal me) {
		return toDetail(findActive(id), me);
	}

	@Transactional(readOnly = true)
	public Recommendations recommendations(Long id) {
		return recommendationService.recommend(findActive(id));
	}

	/** GET /members/me/broadcasts — 내 방송 기록(삭제 제외), 최신순 + 다시보기 상태. */
	@Transactional(readOnly = true)
	public PageResponse<MyBroadcastItem> mine(MemberPrincipal me, Integer page, Integer size) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		Page<Broadcast> broadcasts = broadcastRepository.findMine(me.id(), PageResponse.pageable(page, size, Sort.unsorted()));
		Map<Long, Recording> recordings = recordingsOf(broadcasts.getContent());
		return PageResponse.of(broadcasts, b -> MyBroadcastItem.of(b, recordings.get(b.getId())));
	}

	/** GET /channels/{id}/broadcasts — 채널의 공개 다시보기(ENDED · READY · PUBLIC), 최신순. FE-4 오프라인 화면 · S9 탭. */
	@Transactional(readOnly = true)
	public PageResponse<RecordingSummary> channelRecordings(Long channelId, Integer page, Integer size) {
		if (!channelRepository.existsById(channelId)) {
			throw new ApiException(ErrorCode.CHANNEL_NOT_FOUND);
		}
		// TODO(CMT-1): commentCount
		return PageResponse.of(recordingRepository.findPublicByChannel(channelId,
				PageResponse.pageable(page, size, Sort.unsorted())), r -> RecordingSummary.of(r, 0));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<MyChannelResponse.CurrentBroadcast> findOngoingOf(Long channelId) {
		return broadcastRepository.findOngoing(channelId)
				.map(b -> new MyChannelResponse.CurrentBroadcast(b.getId(), b.getStatus().name()));
	}

	// ---------- 소유자 API ----------

	/** POST /broadcasts/{id}/end — 소유자만. */
	public void end(MemberPrincipal me, Long broadcastId) {
		lifecycle.endByOwner(broadcastId, me);
	}

	/**
	 * PATCH /broadcasts/{id} (§4.6): LIVE · DISCONNECTED 면 방송과 채널 방송 정보를 둘 다 갱신한다(다음 방송 · 재연결 때도 유지).
	 * ENDED 면 title 만 바꾸고 category 가 오면 409 BROADCAST_NOT_LIVE.
	 */
	@Transactional
	public BroadcastDetail update(MemberPrincipal me, Long id, BroadcastUpdateRequest request) {
		Broadcast b = lockOwned(id, me);
		String title = request.title() == null ? null : requireTitle(request.title());
		Category category = request.category();
		if (b.getStatus().isOngoing()) {
			b.updateInfo(title, category);
			b.getChannel().updateInfo(null, null, title, category, null);
		} else {
			if (category != null) {
				throw new ApiException(ErrorCode.BROADCAST_NOT_LIVE, "지난 방송은 카테고리를 바꿀 수 없습니다");
			}
			b.updateInfo(title, null);
		}
		return toDetail(b, me);
	}

	/**
	 * DELETE /broadcasts/{id} (§4.6): 진행 중이면 먼저 종료(kick · 차단 포함) → deleted_at → recording FILE_DELETED →
	 * 커밋 뒤 파일(vod · 썸네일) 삭제. FILE_DELETED 로 먼저 바꿔야 진행 중인 병합이 결과를 버린다. 후원 내역은 남는다.
	 */
	@Transactional
	public void delete(MemberPrincipal me, Long id) {
		Broadcast b = lockOwned(id, me);
		// 녹화는 잠금 읽기로 먼저 가져온다. 일반 SELECT 는 RR 스냅숏이라, 그 사이 병합이 커밋한 READY · file_path 를 못 보고
		// FILE_DELETED 갱신이 그것을 덮어써 고아 vod 가 남는다. 순서: channel → broadcast → recording.
		Optional<Recording> recording = recordingRepository.findByBroadcastIdForUpdate(b.getId());
		if (b.getStatus().isOngoing()) {
			lifecycle.end(b, BroadcastLifecycleService.EndReason.OWNER);
		}
		Instant now = Instant.now();
		b.markDeleted(now);
		recording.ifPresent(r -> r.markFileDeleted(now));
		// TODO(CMT-1): recording 의 댓글을 같은 트랜잭션에서 물리 삭제 — commentRepository.deleteByRecordingId(recordingId)
		String vod = recording.map(Recording::getFilePath).orElse(null);
		String thumbnail = b.getThumbnailPath() != null ? b.getThumbnailPath() : "thumbnails/" + b.getId() + ".jpg";
		AfterCommit.run("녹화 파일 삭제", () -> recordingService.deleteFiles(vod, thumbnail));
	}

	// ---------- 내부 ----------

	private Broadcast findActive(Long id) {
		return broadcastRepository.findActive(id).orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
	}

	/** 채널 → 방송 순서로 잠그고 소유자를 확인한다(상태 전이 · 훅과 직렬화). */
	private Broadcast lockOwned(Long id, MemberPrincipal me) {
		Long channelId = broadcastRepository.findChannelIdOf(id)
				.orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
		Channel channel = channelRepository.findByIdForUpdate(channelId)
				.orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
		OwnershipGuard.requireChannelOwner(channel, me);
		return broadcastRepository.findActiveForUpdate(id)
				.orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
	}

	private BroadcastDetail toDetail(Broadcast b, MemberPrincipal me) {
		long viewers = viewerCounter.count(b.getId());
		Long channelId = b.getChannel().getId();
		Recording recording = recordingRepository.findByBroadcastId(b.getId()).orElse(null);
		boolean isOwner = me != null && b.getChannel().getMember().getId().equals(me.id());
		return new BroadcastDetail(b.getId(), b.getTitle(), CategoryResponse.of(b.getCategory()),
				BroadcastSummary.thumbnailUrl(b.getThumbnailPath()), b.getStatus().name(), b.getStartedAt(), viewers,
				new BroadcastSummary.ChannelRef(channelId, b.getChannel().getName()), b.getEndedAt(),
				b.getSource().name(), viewers >= props.grid().threshold() ? "grid" : "whep",
				new BroadcastDetail.Play("/live/" + channelId + "/whep", "/live/" + channelId + "/index.m3u8"),
				donationRepository.sumApprovedByBroadcast(b.getId()), RecordingRef.of(recording), isOwner);
	}

	private Map<Long, Recording> recordingsOf(List<Broadcast> broadcasts) {
		if (broadcasts.isEmpty()) {
			return Map.of();
		}
		return recordingRepository.findByBroadcastIdIn(broadcasts.stream().map(Broadcast::getId).toList()).stream()
				.collect(Collectors.toMap(r -> r.getBroadcast().getId(), Function.identity()));
	}

	/** 채널 방송 제목과 같은 규칙: 앞뒤 공백 제거, 비어 있거나 제어문자면 400, 100자(코드포인트) 이하. */
	private static String requireTitle(String raw) {
		String value = raw.strip();
		if (value.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "방송 제목을 입력해 주세요");
		}
		if (CONTROL_CHARS.matcher(value).find()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "방송 제목에 사용할 수 없는 문자가 있습니다");
		}
		if (value.codePointCount(0, value.length()) > TITLE_MAX) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "방송 제목은 " + TITLE_MAX + "자 이하여야 합니다");
		}
		return value;
	}
}
