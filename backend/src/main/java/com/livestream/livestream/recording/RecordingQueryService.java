package com.livestream.livestream.recording;

import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.common.security.OwnershipGuard;
import com.livestream.livestream.donation.DonationRepository;
import com.livestream.livestream.recording.dto.RecordingDetail;
import com.livestream.livestream.recording.dto.RecordingSummary;
import com.livestream.livestream.recording.dto.VideoTokenResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다시보기 조회 · 공개 설정 · 비공개 재생 (DESIGN §4.10 서빙, §4.16 /recordings*, §4.8).
 * 삭제된 방송의 다시보기는 어디서나 404. 비공개는 주인만 보고(남은 403), 공개 설정 · video-token 도 주인만.
 */
@Service
@RequiredArgsConstructor
public class RecordingQueryService {

	private final RecordingRepository recordingRepository;
	private final VideoTokenService videoTokenService;
	private final RecordingFiles files;
	private final DonationRepository donationRepository;

	/** GET /recordings?sort=recent — 공개(READY · PUBLIC) 다시보기, 방송 시작 최신순. 정렬은 recent 하나뿐이다. */
	@Transactional(readOnly = true)
	public PageResponse<RecordingSummary> list(String sort, Integer page, Integer size) {
		if (sort != null && !sort.equals("recent")) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "sort는 recent만 지원합니다");
		}
		// TODO(CMT-1): commentCount
		return PageResponse.of(recordingRepository.findPublic(PageResponse.pageable(page, size, Sort.unsorted())),
				r -> RecordingSummary.of(r, 0));
	}

	@Transactional(readOnly = true)
	public RecordingDetail get(Long id, MemberPrincipal me) {
		Recording r = findActive(id);
		boolean owner = isOwner(r, me);
		if (r.getVisibility() == Visibility.PRIVATE && !owner) {
			throw new ApiException(ErrorCode.FORBIDDEN, "비공개 다시보기입니다");
		}
		return toDetail(r, owner);
	}

	/** PATCH /recordings/{id} — 주인만. 상태와 상관없이 바꿀 수 있다(병합 중에 미리 정해 둘 수 있게). */
	@Transactional
	public RecordingDetail updateVisibility(Long id, MemberPrincipal me, Visibility visibility) {
		Recording r = findActive(id);
		OwnershipGuard.requireChannelOwner(r.getBroadcast().getChannel(), me);
		if (visibility == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "visibility를 입력해 주세요");
		}
		recordingRepository.updateVisibility(id, visibility, Instant.now());
		return toDetail(findActive(id), true);
	}

	/** POST /recordings/{id}/video-token — 주인만, READY 일 때만. */
	@Transactional(readOnly = true)
	public VideoTokenResponse issueVideoToken(Long id, MemberPrincipal me) {
		Recording r = findActive(id);
		OwnershipGuard.requireChannelOwner(r.getBroadcast().getChannel(), me);
		requireReady(r);
		String token = videoTokenService.issue(id);
		return new VideoTokenResponse(token, "/api/recordings/" + id + "/video?token=" + token);
	}

	/**
	 * GET /recordings/{id}/video?token= — 토큰(aud · sub · 만료)이 맞으면 vod 파일 경로. 로그인 헤더 없이 &lt;video&gt; 가 부르므로
	 * 토큰이 곧 권한이다. 토큰이 틀리면 403(재발급해야 함), 그 뒤에 지워졌거나 READY 가 아니면 404 · 409.
	 */
	@Transactional(readOnly = true)
	public Path videoFile(Long id, String token) {
		if (!videoTokenService.isValidFor(token, id)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "재생 토큰이 없거나 만료되었습니다");
		}
		Recording r = findActive(id);
		requireReady(r);
		Path path = files.resolve(r.getFilePath());
		if (path == null || !Files.isRegularFile(path)) {
			throw new ApiException(ErrorCode.RECORDING_NOT_FOUND, "다시보기 파일이 없습니다");
		}
		return path;
	}

	// ---------- 내부 ----------

	private Recording findActive(Long id) {
		return recordingRepository.findActive(id).orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
	}

	private static boolean isOwner(Recording r, MemberPrincipal me) {
		return me != null && r.getBroadcast().getChannel().getMember().getId().equals(me.id());
	}

	private static void requireReady(Recording r) {
		if (r.getStatus() != RecordingStatus.READY || r.getFilePath() == null) {
			throw new ApiException(ErrorCode.RECORDING_NOT_READY);
		}
	}

	private RecordingDetail toDetail(Recording r, boolean owner) {
		// TODO(CMT-1): commentCount
		return RecordingDetail.of(r, 0, donationRepository.sumApprovedByBroadcast(r.getBroadcast().getId()), owner);
	}
}
