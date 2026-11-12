package com.livestream.livestream.comment;

import com.livestream.livestream.comment.dto.CommentResponse;
import com.livestream.livestream.comment.dto.CommentUpdateRequest;
import com.livestream.livestream.comment.dto.CommentWriteRequest;
import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Comment", description = "다시보기 · 게시글 댓글(대댓글 1단계)")
@RestController
@RequiredArgsConstructor
public class CommentController {

	private final CommentService commentService;

	@Operation(summary = "다시보기 댓글 목록", description = "누구나(비공개 다시보기는 주인만, 아니면 403). 평면 목록 createdAt 오름차순, 트리는 parentId 로")
	@GetMapping("/api/recordings/{id}/comments")
	public PageResponse<CommentResponse> listOfRecording(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return commentService.listOfRecording(id, me, page, size);
	}

	@Operation(summary = "다시보기 댓글 작성", description = "로그인. 녹화가 READY · FILE_DELETED 가 아니면(라이브 · 병합 중 · 실패) 409 RECORDING_COMMENT_NOT_OPEN, 대댓글에 답글이면 409 COMMENT_DEPTH_EXCEEDED → 201")
	@PostMapping("/api/recordings/{id}/comments")
	public ResponseEntity<CommentResponse> writeOnRecording(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@Valid @RequestBody CommentWriteRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(commentService.writeOnRecording(me, id, request));
	}

	@Operation(summary = "게시글 댓글 목록", description = "누구나. 평면 목록 createdAt 오름차순")
	@GetMapping("/api/posts/{id}/comments")
	public PageResponse<CommentResponse> listOfPost(@PathVariable Long id,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return commentService.listOfPost(id, page, size);
	}

	@Operation(summary = "게시글 댓글 작성", description = "로그인 → 201")
	@PostMapping("/api/posts/{id}/comments")
	public ResponseEntity<CommentResponse> writeOnPost(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@Valid @RequestBody CommentWriteRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(commentService.writeOnPost(me, id, request));
	}

	@Operation(summary = "댓글 수정", description = "작성자만(채널 주인도 남의 댓글 불가 → 403). 삭제된 댓글은 404")
	@PatchMapping("/api/comments/{id}")
	public CommentResponse update(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@Valid @RequestBody CommentUpdateRequest request) {
		return commentService.update(me, id, request);
	}

	@Operation(summary = "댓글 삭제", description = "작성자만. 대댓글이 있으면 목록에 \"삭제된 댓글\"로 남는다 → 204")
	@DeleteMapping("/api/comments/{id}")
	public ResponseEntity<Void> delete(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		commentService.delete(me, id);
		return ResponseEntity.noContent().build();
	}
}
