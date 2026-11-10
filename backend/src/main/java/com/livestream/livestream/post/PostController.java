package com.livestream.livestream.post;

import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.post.dto.PostResponse;
import com.livestream.livestream.post.dto.PostSummary;
import com.livestream.livestream.post.dto.PostUpdateRequest;
import com.livestream.livestream.post.dto.PostWriteRequest;
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

@Tag(name = "Post", description = "채널 커뮤니티 게시글")
@RestController
@RequiredArgsConstructor
public class PostController {

	private final PostService postService;

	@Operation(summary = "채널 게시글 목록", description = "누구나. 최신순")
	@GetMapping("/api/channels/{channelId}/posts")
	public PageResponse<PostSummary> list(@PathVariable Long channelId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return postService.list(channelId, page, size);
	}

	@Operation(summary = "게시글 작성", description = "로그인. 내 채널에 쓴다 → 201")
	@PostMapping("/api/channels/me/posts")
	public ResponseEntity<PostResponse> write(@CurrentMember MemberPrincipal me,
			@Valid @RequestBody PostWriteRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(postService.write(me, request));
	}

	@Operation(summary = "게시글 상세", description = "누구나. isOwner면 수정 · 삭제 버튼 표시")
	@GetMapping("/api/posts/{id}")
	public PostResponse get(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return postService.get(id, me);
	}

	@Operation(summary = "게시글 수정", description = "채널 주인만. 보낸 필드만 반영. 다른 회원은 403")
	@PatchMapping("/api/posts/{id}")
	public PostResponse update(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@RequestBody PostUpdateRequest request) {
		return postService.update(me, id, request);
	}

	@Operation(summary = "게시글 삭제", description = "채널 주인만. 댓글도 함께 삭제 → 204")
	@DeleteMapping("/api/posts/{id}")
	public ResponseEntity<Void> delete(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		postService.delete(me, id);
		return ResponseEntity.noContent().build();
	}
}
