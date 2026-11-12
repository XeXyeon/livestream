package com.livestream.livestream.comment;

import com.livestream.livestream.comment.dto.CommentResponse;
import com.livestream.livestream.comment.dto.CommentUpdateRequest;
import com.livestream.livestream.comment.dto.CommentWriteRequest;
import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.common.security.OwnershipGuard;
import com.livestream.livestream.member.Member;
import com.livestream.livestream.member.MemberRepository;
import com.livestream.livestream.post.Post;
import com.livestream.livestream.post.PostRepository;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.RecordingStatus;
import com.livestream.livestream.recording.Visibility;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글 (DESIGN §4.12 · §4.8). 다시보기 · 게시글 두 묶음이 같은 서비스.
 * <ul>
 *   <li>읽기 · 쓰기 모두 대상에 접근할 수 있어야 한다: 비공개 다시보기는 채널 주인만(아니면 403), 삭제된 방송의 다시보기는 404.</li>
 *   <li>다시보기 댓글은 녹화가 READY · FILE_DELETED(= 방송 종료 뒤 영상이 만들어진 것)일 때만 쓴다. 라이브 중 · 병합 중 ·
 *       병합 실패는 409 RECORDING_COMMENT_NOT_OPEN(§4.8). 목록은 상태와 무관하게 본다.</li>
 *   <li>수정 · 삭제는 작성자만(채널 주인도 남의 댓글 불가). 삭제는 표시만 하고, 대댓글이 남아 있으면 목록에 "삭제된 댓글"로 남는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CommentService {

	static final int CONTENT_MAX = 500;
	/** 개행 · 탭만 허용한다. */
	private static final Pattern CONTENT_CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
	private static final Sort OLDEST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

	private final CommentRepository commentRepository;
	private final RecordingRepository recordingRepository;
	private final PostRepository postRepository;
	private final MemberRepository memberRepository;

	// ---------- 다시보기 ----------

	@Transactional(readOnly = true)
	public PageResponse<CommentResponse> listOfRecording(Long recordingId, MemberPrincipal me, Integer page, Integer size) {
		Recording r = readableRecording(recordingId, me);
		Long ownerId = ownerOf(r);
		return PageResponse.of(commentRepository.findVisibleByRecording(r.getId(), PageResponse.pageable(page, size, OLDEST)),
				c -> CommentResponse.of(c, ownerId));
	}

	@Transactional
	public CommentResponse writeOnRecording(MemberPrincipal me, Long recordingId, CommentWriteRequest request) {
		requireLogin(me);
		// 공유 잠금을 먼저 잡는다: 방송 삭제(댓글 물리 삭제)와 직렬화해 삭제 뒤에 댓글이 남지 않게 하고, 상태를 최신으로 읽는다.
		recordingRepository.findByIdForShare(recordingId).orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
		Recording r = readableRecording(recordingId, me);
		if (r.getStatus() != RecordingStatus.READY && r.getStatus() != RecordingStatus.FILE_DELETED) {
			throw new ApiException(ErrorCode.RECORDING_COMMENT_NOT_OPEN,
					r.getStatus() == RecordingStatus.RECORDING ? "라이브가 끝난 뒤에 댓글을 쓸 수 있습니다"
							: "다시보기가 준비된 뒤에 댓글을 쓸 수 있습니다");
		}
		String content = content(request.content());
		Comment parent = parent(request.parentId(), r, null);
		Comment saved = commentRepository.save(Comment.onRecording(author(me), r, parent, content));
		return CommentResponse.of(saved, ownerOf(r));
	}

	// ---------- 게시글 ----------

	@Transactional(readOnly = true)
	public PageResponse<CommentResponse> listOfPost(Long postId, Integer page, Integer size) {
		Post p = findPost(postId);
		Long ownerId = p.getChannel().getMember().getId();
		return PageResponse.of(commentRepository.findVisibleByPost(p.getId(), PageResponse.pageable(page, size, OLDEST)),
				c -> CommentResponse.of(c, ownerId));
	}

	@Transactional
	public CommentResponse writeOnPost(MemberPrincipal me, Long postId, CommentWriteRequest request) {
		requireLogin(me);
		// 게시글 삭제와 직렬화: 삭제가 먼저 커밋되면 404(잠금 없이 넣으면 FK 오류 500).
		postRepository.findByIdForShare(postId).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
		Post p = findPost(postId);
		String content = content(request.content());
		Comment parent = parent(request.parentId(), null, p);
		Comment saved = commentRepository.save(Comment.onPost(author(me), p, parent, content));
		return CommentResponse.of(saved, p.getChannel().getMember().getId());
	}

	// ---------- 수정 · 삭제 ----------

	/** 작성자만. 비공개로 바뀐 다시보기의 댓글은 (주인이 아니면) 고칠 수 없다 — 지우기는 된다. */
	@Transactional
	public CommentResponse update(MemberPrincipal me, Long commentId, CommentUpdateRequest request) {
		Comment c = findActive(commentId);
		OwnershipGuard.requireAuthor(c, me);
		Long ownerId = c.getRecording() != null
				? ownerOf(readableRecording(c.getRecording().getId(), me))
				: findPost(c.getPost().getId()).getChannel().getMember().getId();
		c.edit(content(request.content()));
		flushOrNotFound(); // updatedAt 을 응답에 반영
		return CommentResponse.of(c, ownerId);
	}

	@Transactional
	public void delete(MemberPrincipal me, Long commentId) {
		Comment c = findActive(commentId);
		OwnershipGuard.requireAuthor(c, me);
		c.markDeleted(Instant.now());
		flushOrNotFound();
	}

	// ---------- 내부 ----------

	/** 읽은 뒤 방송 삭제가 댓글을 물리 삭제하고 먼저 커밋하면 UPDATE 가 0행이다 → 500 대신 404. */
	private void flushOrNotFound() {
		try {
			commentRepository.flush();
		} catch (OptimisticLockingFailureException e) {
			throw new ApiException(ErrorCode.COMMENT_NOT_FOUND);
		}
	}

	/** 삭제된 방송의 다시보기는 404, 비공개는 채널 주인만(403). */
	private Recording readableRecording(Long recordingId, MemberPrincipal me) {
		Recording r = recordingRepository.findActive(recordingId)
				.orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
		boolean owner = me != null && ownerOf(r).equals(me.id());
		if (r.getVisibility() == Visibility.PRIVATE && !owner) {
			throw new ApiException(ErrorCode.FORBIDDEN, "비공개 다시보기입니다");
		}
		return r;
	}

	private static Long ownerOf(Recording r) {
		return r.getBroadcast().getChannel().getMember().getId();
	}

	private Post findPost(Long postId) {
		return postRepository.findWithChannel(postId).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
	}

	private Comment findActive(Long commentId) {
		return commentRepository.findActive(commentId).orElseThrow(() -> new ApiException(ErrorCode.COMMENT_NOT_FOUND));
	}

	/**
	 * 답글 대상 검증: 있고 삭제 안 됨(404), 같은 다시보기 · 게시글(400 — 대상을 먼저 봐서 다른 글의 댓글 구조를 드러내지 않는다),
	 * 대댓글이 아님(409).
	 */
	private Comment parent(Long parentId, Recording recording, Post post) {
		if (parentId == null) {
			return null;
		}
		Comment parent = findActive(parentId);
		boolean sameTarget = recording != null
				? parent.getRecording() != null && Objects.equals(parent.getRecording().getId(), recording.getId())
				: parent.getPost() != null && Objects.equals(parent.getPost().getId(), post.getId());
		if (!sameTarget) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "다른 글의 댓글에는 답글을 달 수 없습니다");
		}
		if (parent.getParent() != null) {
			throw new ApiException(ErrorCode.COMMENT_DEPTH_EXCEEDED);
		}
		return parent;
	}

	private Member author(MemberPrincipal me) {
		return memberRepository.findById(me.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
	}

	private static void requireLogin(MemberPrincipal me) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
	}

	private static String content(String raw) {
		String value = raw == null ? "" : raw.strip();
		if (value.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "댓글을 입력해 주세요");
		}
		if (CONTENT_CONTROL_CHARS.matcher(value).find()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "댓글에 사용할 수 없는 문자가 있습니다");
		}
		if (value.codePointCount(0, value.length()) > CONTENT_MAX) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "댓글은 " + CONTENT_MAX + "자 이하여야 합니다");
		}
		return value;
	}
}
