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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 다시보기 조회 · 공개 설정 · 비공개 재생 (DESIGN §4.10 서빙, §4.16 /recordings*, §4.8).
 * 삭제된 방송의 다시보기는 어디서나 404. 비공개는 주인만 보고(남은 403), 공개 설정 · video-token 도 주인만.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingQueryService {

	private final RecordingRepository recordingRepository;
	private final VideoTokenService videoTokenService;
	private final RecordingFiles files;
	private final DonationRepository donationRepository;
	private final TransactionTemplate tx;

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

	/**
	 * PATCH /recordings/{id} — 주인만. 상태와 상관없이 바꿀 수 있다(병합 중에 미리 정해 둘 수 있게). DESIGN §4.10:
	 * <ul>
	 *   <li>recording 을 잠근 채로 한다. 병합의 READY 저장 · 방송 삭제 · 보관 정리도 같은 행을 잠그므로 폴더 선택이 엇갈리지 않는다.</li>
	 *   <li>READY 면 영상 파일을 공개 설정에 맞는 폴더로 옮기고({@code vod/} ↔ {@code private/}) file_path 를 바꾼다.
	 *       옮기기에 실패하면 500 FILE_MOVE_FAILED 로 되돌린다. Caddy 는 {@code vod/} 만 내주므로 비공개로 옮긴 순간 예전
	 *       {@code /vod/<uuid>.mp4} 주소는 404 다(REC-2#1).</li>
	 *   <li>READY 가 아니면 visibility 만 바꾼다(병합이 READY 로 바꿀 때 그 값으로 폴더를 고른다).</li>
	 * </ul>
	 */
	@Transactional
	public RecordingDetail updateVisibility(Long id, MemberPrincipal me, Visibility visibility) {
		// 주인 확인은 엔티티를 올리지 않는 쿼리로 먼저 한다: 남의 행을 잠그지 않고, 이 트랜잭션의 첫 엔티티 읽기를 잠금 읽기로 하기 위해서다
		// (일반 SELECT 로 먼저 올리면 영속성 컨텍스트의 예전 status · file_path 를 쓰게 된다).
		Long ownerId = recordingRepository.findOwnerMemberIdOfActive(id)
				.orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		if (!ownerId.equals(me.id())) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		if (visibility == null) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "visibility를 입력해 주세요");
		}
		Recording r = recordingRepository.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
		String target = r.getStatus() == RecordingStatus.READY ? RecordingFiles.videoPathFor(r.getFilePath(), visibility) : null;
		if (target == null) {
			if (visibility != r.getVisibility()) {
				recordingRepository.updateVisibility(id, visibility, Instant.now());
			}
			return toDetail(findActive(id), true);
		}
		String current = files.locateVideo(r.getFilePath());
		if (current == null) {
			log.warn("공개 설정 변경: 영상 파일이 두 폴더 모두에 없음: recording={} file={}", id, r.getFilePath());
		} else if (!current.equals(target)) {
			try {
				files.moveVideo(current, target);
			} catch (IOException e) {
				log.error("영상 파일 이동 실패: recording={} {} → {}", id, current, target, e);
				throw new ApiException(ErrorCode.FILE_MOVE_FAILED);
			}
			if (visibility == Visibility.PUBLIC) {
				revertToPrivateOnRollback(id, current, target);
			}
		}
		if (visibility != r.getVisibility() || !target.equals(r.getFilePath())) {
			recordingRepository.updateVisibilityAndFilePath(id, visibility, target, Instant.now());
		}
		return toDetail(findActive(id), true);
	}

	/**
	 * 공개로 옮긴 뒤(private/ → vod/) 트랜잭션이 롤백되면 private/ 로 되돌린다. 반대 방향(비공개로 옮긴 뒤 롤백)은 되돌리지 않는다:
	 * 되돌리기는 잠금이 풀린 뒤에 돌아 다른 요청과 엇갈릴 수 있으므로, 엇갈려도 노출이 아니라 "공개 영상이 잠깐 안 열림" 쪽으로 남게 한다
	 * (그 상태는 재생 GET 과 다음 PATCH 가 맞춘다).
	 */
	private void revertToPrivateOnRollback(Long id, String privatePath, String publicPath) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_ROLLED_BACK) {
					return;
				}
				try {
					files.moveVideo(publicPath, privatePath);
				} catch (IOException e) {
					log.error("롤백 뒤 비공개 폴더로 되돌리기 실패(재생 GET 이 다시 맞춘다): recording={} {} → {}", id, publicPath, privatePath, e);
				}
			}
		});
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
	 * 파일은 {@code vod/} · {@code private/} 둘 다 본다: file_path 와 다른 곳에 있거나 공개 설정과 다른 폴더에 있으면 잠그고 맞춘다.
	 */
	public Path videoFile(Long id, String token) {
		if (!videoTokenService.isValidFor(token, id)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "재생 토큰이 없거나 만료되었습니다");
		}
		Recording r = findActive(id);
		requireReady(r);
		String found = files.locateVideo(r.getFilePath());
		String expected = found == null ? null : RecordingFiles.videoPathFor(found, r.getVisibility());
		if (expected != null && (!found.equals(r.getFilePath()) || !found.equals(expected))) {
			// file_path 와 다른 폴더에서 찾았거나 공개 설정과 다른 폴더에 있다(이동 뒤 커밋 실패 · 되돌리기 실패). 잠그고 맞춘다.
			found = tx.execute(s -> reconcile(id));
		}
		Path path = files.resolve(found);
		if (path == null) {
			throw new ApiException(ErrorCode.RECORDING_NOT_FOUND, "다시보기 파일이 없습니다");
		}
		return path;
	}

	/**
	 * 잠금을 잡고 영상 파일을 공개 설정에 맞는 폴더로 옮긴 뒤 file_path 를 맞춘다. 특히 비공개인데 {@code vod/} 에 있으면(노출) 여기서 거둔다.
	 *
	 * @return 지금 파일이 있는 상대 경로, 없거나 READY 가 아니면 null
	 */
	private String reconcile(Long id) {
		Recording r = recordingRepository.findByIdForUpdate(id).orElse(null);
		if (r == null || r.getStatus() != RecordingStatus.READY) {
			return null;
		}
		String found = files.locateVideo(r.getFilePath());
		if (found == null) {
			return null;
		}
		String target = RecordingFiles.videoPathFor(found, r.getVisibility());
		if (target != null && !found.equals(target)) {
			try {
				files.moveVideo(found, target);
				log.warn("영상 파일을 공개 설정에 맞는 폴더로 옮김: recording={} {} → {}", id, found, target);
				found = target;
			} catch (IOException e) {
				log.error("영상 파일을 공개 설정에 맞는 폴더로 옮기지 못함: recording={} {} → {}", id, found, target, e);
			}
		}
		if (!found.equals(r.getFilePath())) {
			recordingRepository.updateFilePath(id, found);
		}
		return found;
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
