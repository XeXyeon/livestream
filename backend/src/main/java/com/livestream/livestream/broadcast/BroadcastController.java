package com.livestream.livestream.broadcast;

import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Broadcast", description = "방송")
@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
public class BroadcastController {

	private final BroadcastService broadcastService;

	@Operation(summary = "방송 종료", description = "소유자만. LIVE면 송출 연결도 끊고, 30초 동안 같은 채널의 송출을 거절한다. "
			+ "프론트는 whip.close() → stream.stop() 뒤에 부른다. 이미 ENDED면 409 BROADCAST_ALREADY_ENDED")
	@PostMapping("/{id}/end")
	public ResponseEntity<Void> end(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		broadcastService.end(me, id);
		return ResponseEntity.noContent().build();
	}
}
