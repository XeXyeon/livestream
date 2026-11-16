package com.livestream.livestream.clip;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastStatus;
import com.livestream.livestream.clip.dto.ClipDraftDetail;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.common.tx.AfterCommit;
import com.livestream.livestream.member.MemberRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 라이브 클립 초안 (DESIGN §4.18 라이브 클립 초안 · §4.16 clip-drafts, CLIP-3).
 * <ul>
 *   <li>만들기: 방송 LIVE · 시작 뒤 {@code app.clip.min-seconds} 이상. 창은 요청 시각 기준
 *       {@code [max(startedAt, now − live-window-seconds), now)}. 회원당 초안 1개(이전 초안은 행 · 파일을 지운다),
 *       PENDING 초안이나 PROCESSING 클립이 있으면 409. 커밋 뒤 처리기({@link ClipProcessingService#enqueueDraft})가 받는다.</li>
 *   <li>조회 · 재생은 만든 회원만(다른 회원은 404). READY 면 응답에 재생 토큰 주소를 넣는다.</li>
 *   <li>보관 정리: 1분마다 {@code draft-ttl} 이 지난 초안 가운데 처리 중 클립이 가리키지 않는 것의 행 · 파일을 지운다.</li>
 * </ul>
 * 초안에서 클립 만들기는 {@link ClipService#createFromDraft}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClipDraftService {

	static final String TOO_EARLY = "방송 시작 직후에는 만들 수 없습니다";

	private final ClipDraftRepository draftRepository;
	private final ClipRepository clipRepository;
	private final BroadcastRepository broadcastRepository;
	private final MemberRepository memberRepository;
	private final ClipFiles files;
	private final ClipDraftVideoTokenService videoTokenService;
	private final ClipProcessingService processingService;
	private final AppProperties props;
	private final TransactionTemplate tx;

	/**
	 * POST /broadcasts/{id}/clip-drafts — 최근 창을 받아 둘 초안을 PENDING 으로 만든다.
	 * <p>READ_COMMITTED: 이전 초안이 없는 회원의 {@code member_id = ? FOR UPDATE} 가 REPEATABLE_READ 의 갭 잠금을 걸면, 서로 다른
	 * 회원이 동시에 첫 초안을 만들 때 서로의 INSERT 를 막아 데드락이 난다. 잠금 순서는 broadcast(공유) → member → 이전 초안을
	 * 가리키는 clip → clip_draft 이고(방송 삭제: broadcast → … → clip → clip_draft), 동시 처리 검사는 잠근 뒤 최신 커밋을 본다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ClipDraftDetail create(Long broadcastId, MemberPrincipal me) {
		requireLogin(me);
		Broadcast b = broadcastRepository.findActiveForShare(broadcastId)
				.orElseThrow(() -> new ApiException(ErrorCode.BROADCAST_NOT_FOUND));
		if (b.getStatus() != BroadcastStatus.LIVE) {
			throw new ApiException(ErrorCode.BROADCAST_NOT_LIVE);
		}
		// 초 단위로 내림한다. 창 끝 = started_at + elapsed ≤ now, 처리기 · 재시작 복구 모두 started_at + window_start_seconds 로
		// 같은 구간을 다시 만든다.
		long elapsed = Duration.between(b.getStartedAt(), Instant.now()).toSeconds();
		if (elapsed < props.clip().minSeconds()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, TOO_EARLY);
		}
		int window = (int) Math.min(props.clip().liveWindowSeconds(), elapsed);
		int windowStart = (int) (elapsed - window);

		// 같은 회원의 동시 요청은 회원 행에서 한 줄로 선다.
		memberRepository.findByIdForUpdate(me.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		// 회원당 초안 1개: 이전 초안(READY · FAILED)은 행을 지우고 커밋 뒤 파일을 지운다. 지울 때 FK SET NULL 이 고칠 클립을 먼저 잠근다.
		List<Long> previousIds = draftRepository.findIdsByMemberId(me.id());
		if (!previousIds.isEmpty()) {
			clipRepository.findByDraftIdsForUpdate(previousIds);
		}
		List<ClipDraft> previous = draftRepository.findByMemberIdForUpdate(me.id());
		// 잠근 뒤 본다: 방금 커밋된 초안 클립(초안 행 공유 잠금을 기다렸다)이나 받는 중인 초안이 있으면 지우지 않는다.
		if (previous.stream().anyMatch(ClipDraft::isPending)
				|| clipRepository.existsByCreatorIdAndStatus(me.id(), ClipStatus.PROCESSING)) {
			throw new ApiException(ErrorCode.CLIP_IN_PROGRESS);
		}
		if (!previous.isEmpty()) {
			List<String> paths = previous.stream().map(ClipDraft::getFilePath).filter(Objects::nonNull).toList();
			draftRepository.deleteAll(previous);
			deleteFilesAfterCommit(paths);
		}

		ClipDraft draft = draftRepository.save(ClipDraft.create(b, memberRepository.getReferenceById(me.id()), windowStart, window));
		Long id = draft.getId();
		AfterCommit.run("클립 초안 받기 줄 세우기", () -> processingService.enqueueDraft(id));
		return toDetail(draft);
	}

	/** GET /clip-drafts/{id} — 만든 회원만. READY 면 재생 토큰 주소. */
	@Transactional(readOnly = true)
	public ClipDraftDetail get(Long id, MemberPrincipal me) {
		requireLogin(me);
		return toDetail(draftRepository.findByIdAndMemberId(id, me.id())
				.orElseThrow(() -> new ApiException(ErrorCode.CLIP_DRAFT_NOT_FOUND)));
	}

	/** GET /clip-drafts/{id}/video?token= — 토큰(aud · sub · 만료)이 곧 권한이다. 틀리면 401, 지워짐 404, READY 아님 409. */
	public Path videoFile(Long id, String token) {
		if (!videoTokenService.isValidFor(token, id)) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED, "재생 토큰이 없거나 만료되었습니다");
		}
		ClipDraft d = tx.execute(s -> draftRepository.findById(id).orElse(null));
		if (d == null) {
			throw new ApiException(ErrorCode.CLIP_DRAFT_NOT_FOUND);
		}
		if (!d.isReady()) {
			throw new ApiException(ErrorCode.CLIP_DRAFT_NOT_READY);
		}
		Path path = files.resolve(d.getFilePath());
		if (path == null || !Files.isRegularFile(path)) {
			throw new ApiException(ErrorCode.CLIP_DRAFT_NOT_FOUND, "클립 초안 파일이 없습니다");
		}
		return path;
	}

	/**
	 * 방송 삭제(ClipService.deleteAllOfBroadcast 를 거쳐 BroadcastService.delete)와 같은 트랜잭션에서 그 방송의 초안 행을 지우고
	 * 커밋 뒤 파일을 지운다. 잠금은 클립 다음 자리. 받는 중인 초안은 처리기가 저장 직전에 없어진 것을 보고 파일을 버린다.
	 */
	@Transactional
	public void deleteAllOfBroadcast(Long broadcastId) {
		List<ClipDraft> drafts = draftRepository.findByBroadcastIdForUpdate(broadcastId);
		if (drafts.isEmpty()) {
			return;
		}
		List<String> paths = drafts.stream().map(ClipDraft::getFilePath).filter(Objects::nonNull).toList();
		draftRepository.deleteAll(drafts);
		deleteFilesAfterCommit(paths);
	}

	@Scheduled(fixedDelay = 60_000)
	public void purgeExpired() {
		int purged = purgeExpired(Instant.now());
		if (purged > 0) {
			log.info("보관 시간이 지난 클립 초안 {}건 삭제", purged);
		}
	}

	/**
	 * {@code created_at < now − draft-ttl} 인 초안 가운데 처리 중(PROCESSING) 클립이 가리키지 않는 것의 행 · 파일을 지운다.
	 * 초안을 잠근 뒤 다시 본다: 초안에서 클립을 만드는 쪽은 클립 INSERT 의 FK 검사로 초안 행에 공유 잠금을 걸므로, 그 커밋을 기다린
	 * 뒤 새 PROCESSING 클립을 보고 남긴다. READY 클립의 {@code draft_id} 는 FK {@code ON DELETE SET NULL} 로 null 이 된다.
	 * @param now 기준 시각(테스트에서 주입)
	 * @return 지운 건수
	 */
	public int purgeExpired(Instant now) {
		Instant before = now.minus(props.clip().draftTtl());
		// READ_COMMITTED: 클립 잠금 읽기(draft_id 색인)가 갭 잠금을 걸어 초안에서 클립 만들기의 INSERT 를 막지 않게 한다.
		TransactionTemplate rc = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
		rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		int purged = 0;
		for (Long id : draftRepository.findStale(before)) {
			try {
				Boolean done = rc.execute(s -> {
					// 잠금 순서 clip → clip_draft(방송 삭제와 같다): 초안 삭제의 FK SET NULL 이 고칠 클립을 먼저 잠근다.
					clipRepository.findByDraftIdsForUpdate(List.of(id));
					return draftRepository.findByIdForUpdate(id)
							.filter(d -> d.getCreatedAt().isBefore(before) && !clipRepository.existsProcessingByDraftId(id))
							.map(d -> {
								String path = d.getFilePath();
								draftRepository.delete(d);
								if (path != null) {
									deleteFilesAfterCommit(List.of(path));
								}
								return true;
							}).orElse(false);
				});
				if (Boolean.TRUE.equals(done)) {
					purged++;
				}
			} catch (RuntimeException e) {
				// 한 건이 실패해도(데드락 희생 등) 나머지는 정리한다. 다음 회차에 다시 본다.
				log.warn("클립 초안 정리 실패: draft={}", id, e);
			}
		}
		return purged;
	}

	private void deleteFilesAfterCommit(List<String> paths) {
		if (paths.isEmpty()) {
			return;
		}
		AfterCommit.run("클립 초안 파일 삭제", () -> paths.forEach(files::deleteDraftQuietly));
	}

	private ClipDraftDetail toDetail(ClipDraft d) {
		String videoUrl = d.isReady()
				? "/api/clip-drafts/" + d.getId() + "/video?token=" + videoTokenService.issue(d.getId())
				: null;
		return new ClipDraftDetail(d.getId(), d.getBroadcast().getId(), d.getStatus().name(), d.getWindowStartSeconds(),
				d.getWindowSeconds(), videoUrl, d.getCreatedAt());
	}

	private static void requireLogin(MemberPrincipal me) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
	}
}
