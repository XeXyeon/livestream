package com.livestream.livestream.clip;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.dto.BroadcastSummary;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.clip.dto.ClipCreateRequest;
import com.livestream.livestream.clip.dto.ClipDetail;
import com.livestream.livestream.clip.dto.ClipSummary;
import com.livestream.livestream.clip.dto.ClipUpdateRequest;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.common.tx.AfterCommit;
import com.livestream.livestream.member.MemberRepository;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.RecordingStatus;
import com.livestream.livestream.recording.dto.VideoTokenResponse;
import com.livestream.livestream.recording.Visibility;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 클립 생성 · 조회 · 수정 · 삭제 · 권한 (DESIGN §4.18 · §4.16 클립 행 · §4.8).
 * <ul>
 *   <li>만들기는 로그인한 누구나. 다시보기 클립은 recording READY 이고 PUBLIC(PRIVATE 는 채널 주인만), 라이브 클립은 내 READY
 *       초안(CLIP-3, {@link ClipDraftService})에서 — 방송이 그 사이 끝나도 된다.</li>
 *   <li>PROCESSING 으로 저장하고 커밋 뒤에 처리기({@link ClipProcessingService})에 줄 세운다. 회원당 동시 처리 1개.</li>
 *   <li>제목 · 공개 설정 · 삭제 · 비공개 조회 · 재생은 만든 회원 또는 채널 주인.</li>
 *   <li>공개 설정 변경 · 재생 · 파일 맞추기는 다시보기(RecordingQueryService, §4.10)와 같은 폴더 이동 규칙이다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClipService {

	static final String OUT_OF_RANGE = "클립 구간이 영상 범위를 벗어났습니다";

	private final ClipRepository clipRepository;
	private final ClipDraftRepository draftRepository;
	private final ClipDraftService draftService;
	private final RecordingRepository recordingRepository;
	private final ChannelRepository channelRepository;
	private final MemberRepository memberRepository;
	private final ClipFiles files;
	private final ClipVideoTokenService videoTokenService;
	private final ClipProcessingService processingService;
	private final AppProperties props;
	private final TransactionTemplate tx;

	// ---------- 만들기 ----------

	/** POST /recordings/{id}/clips — 다시보기 구간. */
	@Transactional
	public ClipDetail createFromRecording(Long recordingId, MemberPrincipal me, ClipCreateRequest req) {
		requireLogin(me);
		Recording r = recordingRepository.findActive(recordingId)
				.orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
		Broadcast b = r.getBroadcast();
		boolean owner = b.getChannel().getMember().getId().equals(me.id());
		// 비공개는 상태를 알려 주지 않도록 READY 검사보다 먼저 막는다.
		if (r.getVisibility() == Visibility.PRIVATE && !owner) {
			throw new ApiException(ErrorCode.FORBIDDEN, "비공개 다시보기입니다");
		}
		if (r.getStatus() != RecordingStatus.READY) {
			throw new ApiException(ErrorCode.RECORDING_NOT_READY);
		}
		int duration = requireDuration(req.durationSeconds());
		int start = req.startSeconds();
		// 길이를 모르는 READY(ffprobe 실패)는 끝을 검사할 수 없어 시작만 본다. ffmpeg 가 영상 끝에서 멈춘다.
		if (start < 0 || (r.getDurationSeconds() != null && (long) start + duration > r.getDurationSeconds())) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, OUT_OF_RANGE);
		}
		requireNoneInProgress(me);
		Clip clip = Clip.create(r, b, memberRepository.getReferenceById(me.id()), titleOrDefault(req.title(), b),
				ClipSource.RECORDING, start, duration, visibilityOrDefault(req.visibility()));
		return saveAndEnqueue(clip, me);
	}

	/**
	 * POST /clip-drafts/{id}/clips — 내 READY 초안에서 클립(source LIVE). {@code startSeconds} 는 초안 기준이고 저장은 방송 시작 기준
	 * ({@code window_start_seconds + startSeconds}). 방송이 그 사이 끝나도 된다(초안 파일에서 자른다).
	 */
	@Transactional
	public ClipDetail createFromDraft(Long draftId, MemberPrincipal me, ClipCreateRequest req) {
		requireLogin(me);
		ClipDraft d = draftRepository.findByIdAndMemberId(draftId, me.id())
				.orElseThrow(() -> new ApiException(ErrorCode.CLIP_DRAFT_NOT_FOUND));
		Broadcast b = d.getBroadcast();
		if (b.isDeleted()) {
			throw new ApiException(ErrorCode.CLIP_DRAFT_NOT_FOUND);
		}
		if (!d.isReady()) {
			throw new ApiException(ErrorCode.CLIP_DRAFT_NOT_READY);
		}
		int duration = requireDuration(req.durationSeconds());
		int start = req.startSeconds();
		if (start < 0 || (long) start + duration > d.getWindowSeconds()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, OUT_OF_RANGE);
		}
		requireNoneInProgress(me);
		Recording r = recordingRepository.findByBroadcastId(b.getId())
				.orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
		Clip clip = Clip.fromDraft(d, r, memberRepository.getReferenceById(me.id()), titleOrDefault(req.title(), b),
				d.getWindowStartSeconds() + start, duration, visibilityOrDefault(req.visibility()));
		try {
			return saveAndEnqueue(clip, me);
		} catch (DataIntegrityViolationException e) {
			// 읽은 뒤 초안이 보관 정리 · 새 초안으로 지워졌다(클립 INSERT 의 draft_id FK 위반)
			throw new ApiException(ErrorCode.CLIP_DRAFT_NOT_FOUND);
		}
	}

	private ClipDetail saveAndEnqueue(Clip clip, MemberPrincipal me) {
		clipRepository.save(clip);
		Long id = clip.getId();
		AfterCommit.run("클립 처리 줄 세우기", () -> processingService.enqueue(id));
		return toDetail(findActive(id), me);
	}

	// ---------- 조회 ----------

	/** GET /clips/{id} — 누구나. 비공개는 만든 회원 · 채널 주인만(남은 403). */
	@Transactional(readOnly = true)
	public ClipDetail get(Long id, MemberPrincipal me) {
		Clip c = findActive(id);
		if (c.getVisibility() == Visibility.PRIVATE && !c.isManageableBy(memberId(me))) {
			throw new ApiException(ErrorCode.FORBIDDEN, "비공개 클립입니다");
		}
		return toDetail(c, me);
	}

	/** GET /channels/{id}/clips — READY · PUBLIC, 채널 주인이면 비공개도. */
	@Transactional(readOnly = true)
	public PageResponse<ClipSummary> listByChannel(Long channelId, MemberPrincipal me, Integer page, Integer size) {
		Channel channel = channelRepository.findById(channelId)
				.orElseThrow(() -> new ApiException(ErrorCode.CHANNEL_NOT_FOUND));
		boolean owner = me != null && channel.getMember().getId().equals(me.id());
		return PageResponse.of(clipRepository.findReadyByChannel(channelId, owner,
				PageResponse.pageable(page, size, Sort.unsorted())), ClipSummary::of);
	}

	/** GET /recordings/{id}/clips — READY · PUBLIC, 채널 주인이면 비공개도. 삭제된 방송의 다시보기는 404. */
	@Transactional(readOnly = true)
	public PageResponse<ClipSummary> listByRecording(Long recordingId, MemberPrincipal me, Integer page, Integer size) {
		Long ownerId = recordingRepository.findOwnerMemberIdOfActive(recordingId)
				.orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
		boolean owner = me != null && ownerId.equals(me.id());
		return PageResponse.of(clipRepository.findReadyByRecording(recordingId, owner,
				PageResponse.pageable(page, size, Sort.unsorted())), ClipSummary::of);
	}

	/** GET /members/me/clips — 내가 만든 클립 전부(PROCESSING · FAILED 포함, 삭제 제외). */
	@Transactional(readOnly = true)
	public PageResponse<ClipSummary> listMine(MemberPrincipal me, Integer page, Integer size) {
		requireLogin(me);
		return PageResponse.of(clipRepository.findByCreator(me.id(), PageResponse.pageable(page, size, Sort.unsorted())),
				ClipSummary::of);
	}

	// ---------- 수정 · 삭제 ----------

	/**
	 * PATCH /clips/{id} — 만든 회원 · 채널 주인. 클립을 잠근 채로 한다(처리기의 READY 저장 · 삭제와 직렬화).
	 * READY 면 공개 설정에 맞는 폴더로 파일을 옮기고(실패하면 500 FILE_MOVE_FAILED 로 되돌림), 아니면 값만 바꾼다
	 * (처리기가 READY 로 바꿀 때 그 값으로 폴더를 고른다).
	 */
	@Transactional
	public ClipDetail update(Long id, MemberPrincipal me, ClipUpdateRequest req) {
		Clip c = lockManageable(id, me);
		if (req.title() != null) {
			c.rename(titleOrDefault(req.title(), c.getBroadcast()));
		}
		Visibility visibility = req.visibility();
		if (visibility != null) {
			String target = c.getStatus() == ClipStatus.READY ? ClipFiles.videoPathFor(c.getFilePath(), visibility) : null;
			if (target == null) {
				c.changeVisibility(visibility, c.getFilePath());
			} else {
				String current = files.locateVideo(c.getFilePath());
				if (current == null) {
					log.warn("클립 공개 설정 변경: 영상 파일이 두 폴더 모두에 없음: clip={} file={}", id, c.getFilePath());
				} else if (!current.equals(target)) {
					try {
						files.moveVideo(current, target);
					} catch (IOException e) {
						log.error("클립 파일 이동 실패: clip={} {} → {}", id, current, target, e);
						throw new ApiException(ErrorCode.FILE_MOVE_FAILED);
					}
					if (visibility == Visibility.PUBLIC) {
						revertToPrivateOnRollback(id, current, target);
					}
				}
				c.changeVisibility(visibility, target);
			}
		}
		clipRepository.flush();
		return toDetail(findActive(id), me);
	}

	/** DELETE /clips/{id} — 만든 회원 · 채널 주인. soft delete + 커밋 뒤 파일 · 썸네일 삭제. 처리 중이면 처리기가 결과를 버린다. */
	@Transactional
	public void delete(Long id, MemberPrincipal me) {
		Clip c = lockManageable(id, me);
		c.markDeleted(Instant.now());
		deleteFilesAfterCommit(List.of(new FileRefs(c.getId(), c.getFilePath(), c.getThumbnailPath())));
	}

	/**
	 * 방송 삭제(BroadcastService.delete)와 같은 트랜잭션에서 그 방송의 클립을 모두 soft delete 하고 커밋 뒤 파일을 지운다.
	 * 호출 쪽이 channel → broadcast → recording 을 잠근 뒤 부르므로 클립 → 초안 잠금이 그 다음 자리다.
	 */
	@Transactional
	public void deleteAllOfBroadcast(Long broadcastId) {
		List<Clip> clips = clipRepository.findActiveByBroadcastIdForUpdate(broadcastId);
		if (!clips.isEmpty()) {
			Instant now = Instant.now();
			clips.forEach(c -> c.markDeleted(now));
			deleteFilesAfterCommit(clips.stream().map(c -> new FileRefs(c.getId(), c.getFilePath(), c.getThumbnailPath())).toList());
		}
		// 라이브 클립 초안(CLIP-3)도 지운다. 잠금은 클립 다음 자리.
		draftService.deleteAllOfBroadcast(broadcastId);
	}

	private record FileRefs(Long clipId, String filePath, String thumbnailPath) {
	}

	private void deleteFilesAfterCommit(List<FileRefs> refs) {
		AfterCommit.run("클립 파일 삭제", () -> refs.forEach(f -> {
			if (f.filePath() != null) {
				files.deleteVideoQuietly(f.filePath());
			}
			// 썸네일 경로가 비어 있어도(처리 중) 정해진 이름으로 한 번 더 지운다.
			files.deleteThumbnailQuietly(f.thumbnailPath() != null ? f.thumbnailPath() : ClipFiles.thumbnailPathFor(f.clipId()));
		}));
	}

	// ---------- 비공개 재생 ----------

	/** POST /clips/{id}/video-token — 만든 회원 · 채널 주인, READY 일 때만. */
	@Transactional(readOnly = true)
	public VideoTokenResponse issueVideoToken(Long id, MemberPrincipal me) {
		requireLogin(me);
		Clip c = findActive(id);
		if (!c.isManageableBy(me.id())) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		requireReady(c);
		String token = videoTokenService.issue(id);
		return new VideoTokenResponse(token, "/api/clips/" + id + "/video?token=" + token);
	}

	/**
	 * GET /clips/{id}/video?token= — 토큰(aud · sub · 만료)이 곧 권한이다. 틀리면 403, 그 뒤 삭제 · READY 아님은 404 · 409.
	 * 파일이 file_path 와 다른 폴더에 있거나 공개 설정과 다른 폴더에 있으면 잠그고 맞춘다(§4.10 과 같은 규칙).
	 */
	public Path videoFile(Long id, String token) {
		if (!videoTokenService.isValidFor(token, id)) {
			throw new ApiException(ErrorCode.FORBIDDEN, "재생 토큰이 없거나 만료되었습니다");
		}
		Clip c = tx.execute(s -> findActive(id));
		requireReady(c);
		String found = files.locateVideo(c.getFilePath());
		String expected = found == null ? null : ClipFiles.videoPathFor(found, c.getVisibility());
		if (expected != null && (!found.equals(c.getFilePath()) || !found.equals(expected))) {
			found = tx.execute(s -> reconcile(id));
		}
		Path path = files.resolve(found);
		if (path == null) {
			throw new ApiException(ErrorCode.CLIP_NOT_FOUND, "클립 파일이 없습니다");
		}
		return path;
	}

	/** 잠그고 파일을 공개 설정에 맞는 폴더로 옮긴 뒤 file_path 를 맞춘다. @return 지금 파일 경로, 없거나 READY 가 아니면 null */
	private String reconcile(Long id) {
		Clip c = clipRepository.findByIdForUpdate(id).orElse(null);
		if (c == null || c.isDeleted() || c.getStatus() != ClipStatus.READY) {
			return null;
		}
		String found = files.locateVideo(c.getFilePath());
		if (found == null) {
			return null;
		}
		String target = ClipFiles.videoPathFor(found, c.getVisibility());
		if (target != null && !found.equals(target)) {
			try {
				files.moveVideo(found, target);
				log.warn("클립 파일을 공개 설정에 맞는 폴더로 옮김: clip={} {} → {}", id, found, target);
				found = target;
			} catch (IOException e) {
				log.error("클립 파일을 공개 설정에 맞는 폴더로 옮기지 못함: clip={} {} → {}", id, found, target, e);
			}
		}
		if (!found.equals(c.getFilePath())) {
			c.changeVisibility(c.getVisibility(), found);
		}
		return found;
	}

	/** 공개로 옮긴 뒤 롤백되면 비공개 폴더로 되돌린다(RecordingQueryService 와 같은 이유: 노출 쪽으로 남지 않게). */
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
					log.error("롤백 뒤 클립을 비공개 폴더로 되돌리기 실패(재생 GET 이 다시 맞춘다): clip={} {} → {}", id, publicPath, privatePath, e);
				}
			}
		});
	}

	// ---------- 내부 ----------

	private Clip findActive(Long id) {
		return clipRepository.findActive(id).orElseThrow(() -> new ApiException(ErrorCode.CLIP_NOT_FOUND));
	}

	/** 권한을 엔티티 없이 먼저 확인하고(남의 행을 잠그지 않게) 클립을 잠가 읽는다. */
	private Clip lockManageable(Long id, MemberPrincipal me) {
		ClipManagers managers = clipRepository.findManagersOfActive(id)
				.orElseThrow(() -> new ApiException(ErrorCode.CLIP_NOT_FOUND));
		requireLogin(me);
		if (!managers.includes(me.id())) {
			throw new ApiException(ErrorCode.FORBIDDEN);
		}
		Clip c = clipRepository.findByIdForUpdate(id).orElseThrow(() -> new ApiException(ErrorCode.CLIP_NOT_FOUND));
		if (c.isDeleted()) {
			throw new ApiException(ErrorCode.CLIP_NOT_FOUND);
		}
		return c;
	}

	private int requireDuration(Integer seconds) {
		int min = props.clip().minSeconds();
		int max = props.clip().maxSeconds();
		if (seconds == null || seconds < min || seconds > max) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "클립 길이는 " + min + "~" + max + "초입니다");
		}
		return seconds;
	}

	private void requireNoneInProgress(MemberPrincipal me) {
		if (clipRepository.existsByCreatorIdAndStatus(me.id(), ClipStatus.PROCESSING)) {
			throw new ApiException(ErrorCode.CLIP_IN_PROGRESS);
		}
	}

	private static void requireLogin(MemberPrincipal me) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
	}

	private static void requireReady(Clip c) {
		if (c.getStatus() != ClipStatus.READY || c.getFilePath() == null) {
			throw new ApiException(ErrorCode.CLIP_NOT_READY);
		}
	}

	private static String titleOrDefault(String title, Broadcast b) {
		String t = title == null ? "" : title.strip();
		return t.isEmpty() ? b.getTitle() : t;
	}

	private static Visibility visibilityOrDefault(Visibility v) {
		return v == null ? Visibility.PUBLIC : v;
	}

	private static Long memberId(MemberPrincipal me) {
		return me == null ? null : me.id();
	}

	private ClipDetail toDetail(Clip c, MemberPrincipal me) {
		Long meId = memberId(me);
		boolean isOwner = c.isChannelOwnedBy(meId);
		boolean isCreator = c.isCreatedBy(meId);
		Recording r = c.getRecording();
		boolean recordingAvailable = r.getStatus() == RecordingStatus.READY
				&& (r.getVisibility() == Visibility.PUBLIC || isOwner);
		String videoUrl = c.getStatus() == ClipStatus.READY && c.getVisibility() == Visibility.PUBLIC && c.getFilePath() != null
				? "/" + c.getFilePath() : null;
		String failureReason = c.getStatus() == ClipStatus.FAILED && (isOwner || isCreator) ? c.getFailureReason() : null;
		return new ClipDetail(c.getId(), c.getTitle(), c.getStatus().name(), c.getVisibility().name(), c.getSource().name(),
				c.getStartSeconds(), c.getDurationSeconds(), BroadcastSummary.thumbnailUrl(c.getThumbnailPath()),
				c.getCreatedAt(), new ClipSummary.CreatorRef(c.getCreator().getNickname()),
				new ClipSummary.ChannelRef(c.getChannel().getId(), c.getChannel().getName()),
				c.getBroadcast().getId(), r.getId(), recordingAvailable, videoUrl, failureReason, isOwner, isCreator);
	}
}
