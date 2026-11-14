package com.livestream.livestream.recording;

import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.recording.dto.RecordingDetail;
import com.livestream.livestream.recording.dto.RecordingSummary;
import com.livestream.livestream.recording.dto.VideoTokenResponse;
import com.livestream.livestream.recording.dto.VisibilityUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Recording", description = "다시보기")
@RestController
@RequiredArgsConstructor
public class RecordingController {

	private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");

	private final RecordingQueryService recordingQueryService;

	@Operation(summary = "다시보기 목록", description = "누구나. READY · PUBLIC, 방송 시작 최신순. sort는 recent만")
	@GetMapping("/api/recordings")
	public PageResponse<RecordingSummary> list(@RequestParam(required = false) String sort,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return recordingQueryService.list(sort, page, size);
	}

	@Operation(summary = "다시보기 상세", description = "누구나(비공개는 주인만, 남은 403). 삭제된 방송은 404. videoUrl은 READY · PUBLIC 일 때만")
	@GetMapping("/api/recordings/{id}")
	public RecordingDetail get(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return recordingQueryService.get(id, me);
	}

	@Operation(summary = "공개 설정", description = "주인만. 다른 회원은 403")
	@PatchMapping("/api/recordings/{id}")
	public RecordingDetail update(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@Valid @RequestBody VisibilityUpdateRequest request) {
		return recordingQueryService.updateVisibility(id, me, request.visibility());
	}

	@Operation(summary = "비공개 재생 토큰", description = "주인만, READY 일 때. 5분 유효. 만료 뒤 탐색이 403이면 다시 받아 currentTime을 이어 간다")
	@PostMapping("/api/recordings/{id}/video-token")
	public VideoTokenResponse videoToken(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return recordingQueryService.issueVideoToken(id, me);
	}

	@Operation(summary = "비공개 재생", description = "토큰으로 인증(로그인 헤더 불필요). Range 요청이면 206")
	@GetMapping("/api/recordings/{id}/video")
	public ResponseEntity<Resource> video(@PathVariable Long id, @RequestParam(required = false) String token) {
		Resource body = new FileSystemResource(recordingQueryService.videoFile(id, token));
		// Range 헤더가 있으면 Spring MVC 가 ResourceRegion 으로 잘라 206 · Content-Range 를 붙인다.
		return ResponseEntity.ok()
				.contentType(VIDEO_MP4)
				.cacheControl(CacheControl.noStore())
				.header("Referrer-Policy", "no-referrer")
				.body(body);
	}
}
