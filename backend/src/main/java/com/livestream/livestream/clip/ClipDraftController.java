package com.livestream.livestream.clip;

import com.livestream.livestream.clip.dto.ClipCreateRequest;
import com.livestream.livestream.clip.dto.ClipDetail;
import com.livestream.livestream.clip.dto.ClipDraftDetail;
import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Clip Draft", description = "라이브 클립 초안: 최근 2분을 받아 두고 그 안에서 구간을 골라 클립을 만든다(CLIP-3)")
@RestController
@RequiredArgsConstructor
public class ClipDraftController {

	private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");

	private final ClipDraftService draftService;
	private final ClipService clipService;

	@Operation(summary = "라이브 클립 초안 만들기",
			description = "로그인, 본문 없음. 방송 LIVE(아니면 409 BROADCAST_NOT_LIVE), 시작 뒤 5초 이상(아니면 400). "
					+ "받는 중인 초안이나 처리 중인 클립이 있으면 409 CLIP_IN_PROGRESS. 이전 초안은 지운다. 202 + PENDING")
	@PostMapping("/api/broadcasts/{id}/clip-drafts")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ClipDraftDetail create(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return draftService.create(id, me);
	}

	@Operation(summary = "클립 초안 상세",
			description = "만든 회원만(다른 회원은 404 CLIP_DRAFT_NOT_FOUND). READY 면 videoUrl 에 재생 토큰(10분)")
	@GetMapping("/api/clip-drafts/{id}")
	public ClipDraftDetail get(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return draftService.get(id, me);
	}

	@Operation(summary = "클립 초안 영상", description = "토큰으로 인증(로그인 헤더 불필요, 없거나 틀리면 401). READY 아님 409. Range 요청이면 206")
	@GetMapping("/api/clip-drafts/{id}/video")
	public ResponseEntity<Resource> video(@PathVariable Long id, @RequestParam(required = false) String token) {
		Resource body = new FileSystemResource(draftService.videoFile(id, token));
		// Range 헤더가 있으면 Spring MVC 가 ResourceRegion 으로 잘라 206 · Content-Range 를 붙인다.
		return ResponseEntity.ok()
				.contentType(VIDEO_MP4)
				.cacheControl(CacheControl.noStore())
				.header("Referrer-Policy", "no-referrer")
				.body(body);
	}

	@Operation(summary = "초안에서 클립 만들기",
			description = "만든 회원만. 초안 READY(아니면 409 CLIP_DRAFT_NOT_READY), startSeconds 는 초안 기준, 길이 5~60초, "
					+ "start + duration ≤ windowSeconds(아니면 400). 처리 중인 내 클립이 있으면 409. 방송이 끝나도 된다. 202 + PROCESSING")
	@PostMapping("/api/clip-drafts/{id}/clips")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ClipDetail createClip(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@Valid @RequestBody ClipCreateRequest request) {
		return clipService.createFromDraft(id, me, request);
	}
}
