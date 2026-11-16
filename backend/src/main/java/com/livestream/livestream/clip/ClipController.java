package com.livestream.livestream.clip;

import com.livestream.livestream.clip.dto.ClipCreateRequest;
import com.livestream.livestream.clip.dto.ClipDetail;
import com.livestream.livestream.clip.dto.ClipSummary;
import com.livestream.livestream.clip.dto.ClipUpdateRequest;
import com.livestream.livestream.clip.dto.LiveClipCreateRequest;
import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.recording.dto.VideoTokenResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Clip", description = "클립(다시보기 구간 · 라이브 최근 N초)")
@RestController
@RequiredArgsConstructor
public class ClipController {

	private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");

	private final ClipService clipService;

	@Operation(summary = "다시보기에서 클립 만들기",
			description = "로그인. recording READY(아니면 409 RECORDING_NOT_READY), PUBLIC 이거나 주인(아니면 403), 길이 5~60초, "
					+ "구간이 영상 안(아니면 400). 처리 중인 내 클립이 있으면 409 CLIP_IN_PROGRESS. 202 + PROCESSING")
	@PostMapping("/api/recordings/{id}/clips")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ClipDetail createFromRecording(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@Valid @RequestBody ClipCreateRequest request) {
		return clipService.createFromRecording(id, me, request);
	}

	@Operation(summary = "라이브 최근 N초 클립 만들기",
			description = "로그인. 방송 LIVE(아니면 409 BROADCAST_NOT_LIVE), 시작 뒤 N초 이상 지났을 것(아니면 400). 202 + PROCESSING")
	@PostMapping("/api/broadcasts/{id}/clips")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ClipDetail createFromLive(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@Valid @RequestBody LiveClipCreateRequest request) {
		return clipService.createFromLive(id, me, request);
	}

	@Operation(summary = "클립 상세", description = "누구나(비공개는 만든 회원 · 채널 주인만, 남은 403). 삭제 · 없음은 404 CLIP_NOT_FOUND")
	@GetMapping("/api/clips/{id}")
	public ClipDetail get(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return clipService.get(id, me);
	}

	@Operation(summary = "채널 클립 목록", description = "누구나. READY · PUBLIC 최신순, 채널 주인은 비공개 포함")
	@GetMapping("/api/channels/{id}/clips")
	public PageResponse<ClipSummary> listByChannel(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return clipService.listByChannel(id, me, page, size);
	}

	@Operation(summary = "다시보기 클립 목록", description = "누구나. READY · PUBLIC 최신순, 채널 주인은 비공개 포함")
	@GetMapping("/api/recordings/{id}/clips")
	public PageResponse<ClipSummary> listByRecording(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return clipService.listByRecording(id, me, page, size);
	}

	@Operation(summary = "내 클립", description = "로그인. 내가 만든 클립 전부(PROCESSING · FAILED 포함, 삭제 제외) 최신순")
	@GetMapping("/api/members/me/clips")
	public PageResponse<ClipSummary> listMine(@CurrentMember MemberPrincipal me,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return clipService.listMine(me, page, size);
	}

	@Operation(summary = "클립 제목 · 공개 설정", description = "만든 회원 · 채널 주인. 남은 403. READY 면 공개 설정에 맞는 폴더로 파일을 옮긴다")
	@PatchMapping("/api/clips/{id}")
	public ClipDetail update(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@Valid @RequestBody ClipUpdateRequest request) {
		return clipService.update(id, me, request);
	}

	@Operation(summary = "클립 삭제", description = "만든 회원 · 채널 주인. 남은 403. soft delete + 파일 삭제")
	@DeleteMapping("/api/clips/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		clipService.delete(id, me);
	}

	@Operation(summary = "비공개 클립 재생 토큰", description = "만든 회원 · 채널 주인, READY 일 때(아니면 409 CLIP_NOT_READY). 5분 유효")
	@PostMapping("/api/clips/{id}/video-token")
	public VideoTokenResponse videoToken(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return clipService.issueVideoToken(id, me);
	}

	@Operation(summary = "비공개 클립 재생", description = "토큰으로 인증(로그인 헤더 불필요). Range 요청이면 206")
	@GetMapping("/api/clips/{id}/video")
	public ResponseEntity<Resource> video(@PathVariable Long id, @RequestParam(required = false) String token) {
		Resource body = new FileSystemResource(clipService.videoFile(id, token));
		// Range 헤더가 있으면 Spring MVC 가 ResourceRegion 으로 잘라 206 · Content-Range 를 붙인다.
		return ResponseEntity.ok()
				.contentType(VIDEO_MP4)
				.cacheControl(CacheControl.noStore())
				.header("Referrer-Policy", "no-referrer")
				.body(body);
	}
}
