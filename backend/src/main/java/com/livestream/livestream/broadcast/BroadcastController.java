package com.livestream.livestream.broadcast;

import com.livestream.livestream.broadcast.dto.BroadcastDetail;
import com.livestream.livestream.broadcast.dto.BroadcastSummary;
import com.livestream.livestream.broadcast.dto.BroadcastUpdateRequest;
import com.livestream.livestream.broadcast.dto.MyBroadcastItem;
import com.livestream.livestream.broadcast.dto.Recommendations;
import com.livestream.livestream.channel.Category;
import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.recording.dto.RecordingSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 방송 API (DESIGN §4.16). 내 방송 · 채널 방송 기록도 방송 도메인이라 여기 둔다. */
@Tag(name = "Broadcast", description = "방송")
@RestController
@RequiredArgsConstructor
public class BroadcastController {

	private final BroadcastService broadcastService;

	@Operation(summary = "방송 목록", description = "누구나. status는 LIVE(기본) · DISCONNECTED. 시청자 수 내림차순 → 시작 최신순")
	@GetMapping("/api/broadcasts")
	public PageResponse<BroadcastSummary> list(@RequestParam(required = false) BroadcastStatus status,
			@RequestParam(required = false) Category category, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return broadcastService.list(status, category, page, size);
	}

	@Operation(summary = "방송 상세", description = "누구나. recommendedMode · play URL · isOwner 포함. 삭제된 방송은 404")
	@GetMapping("/api/broadcasts/{id}")
	public BroadcastDetail get(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return broadcastService.get(id, me);
	}

	@Operation(summary = "추천", description = "다른 채널의 같은 카테고리 LIVE 최대 4, 없으면 최근 공개 다시보기 4")
	@GetMapping("/api/broadcasts/{id}/recommendations")
	public Recommendations recommendations(@PathVariable Long id) {
		return broadcastService.recommendations(id);
	}

	@Operation(summary = "방송 정보 수정", description = "소유자만. 진행 중이면 채널 방송 정보도 함께 바뀐다. 지난 방송은 제목만(카테고리면 409)")
	@PatchMapping("/api/broadcasts/{id}")
	public BroadcastDetail update(@CurrentMember MemberPrincipal me, @PathVariable Long id,
			@RequestBody BroadcastUpdateRequest request) {
		return broadcastService.update(me, id, request);
	}

	@Operation(summary = "방송 종료", description = "소유자만. LIVE면 송출 연결도 끊고, 30초 동안 같은 채널의 송출을 거절한다. "
			+ "프론트는 whip.close() → stream.stop() 뒤에 부른다. 이미 ENDED면 409 BROADCAST_ALREADY_ENDED")
	@PostMapping("/api/broadcasts/{id}/end")
	public ResponseEntity<Void> end(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		broadcastService.end(me, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "방송 삭제", description = "소유자만. 진행 중이면 먼저 종료. 다시보기 파일도 지워진다(후원 내역은 남음)")
	@DeleteMapping("/api/broadcasts/{id}")
	public ResponseEntity<Void> delete(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		broadcastService.delete(me, id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "내 방송 기록", description = "로그인. 삭제 제외, 최신순, 다시보기 상태 · 공개 여부 포함")
	@GetMapping("/api/members/me/broadcasts")
	public PageResponse<MyBroadcastItem> mine(@CurrentMember MemberPrincipal me,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return broadcastService.mine(me, page, size);
	}

	@Operation(summary = "채널의 공개 다시보기", description = "누구나. 종료되고 다시보기가 READY · PUBLIC 인 방송, 최신순")
	@GetMapping("/api/channels/{id}/broadcasts")
	public PageResponse<RecordingSummary> channelRecordings(@PathVariable Long id,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return broadcastService.channelRecordings(id, page, size);
	}
}
