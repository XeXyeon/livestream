package com.livestream.livestream.channel;

import com.livestream.livestream.channel.dto.ChannelResponse;
import com.livestream.livestream.channel.dto.ChannelUpdateRequest;
import com.livestream.livestream.channel.dto.MyChannelResponse;
import com.livestream.livestream.channel.dto.StreamKeyResponse;
import com.livestream.livestream.channel.dto.WidgetKeyResponse;
import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Channel", description = "채널 · 방송 정보 · 스트림 키")
@RestController
@RequestMapping("/api/channels")
@RequiredArgsConstructor
public class ChannelController {

	private final ChannelService channelService;

	@Operation(summary = "채널 공개 정보", description = "누구나. liveBroadcastId는 진행 중 방송이 없으면 null")
	@GetMapping("/{id}")
	public ChannelResponse get(@PathVariable Long id) {
		return channelService.getPublic(id);
	}

	@Operation(summary = "내 채널", description = "로그인 필요. 스트림 키 · 위젯 키 · 송출 안내 포함")
	@GetMapping("/me")
	public MyChannelResponse me(@CurrentMember MemberPrincipal me) {
		return channelService.getMine(me);
	}

	@Operation(summary = "내 채널 정보 수정", description = "보낸 필드만 반영. 다음 방송의 제목 · 카테고리도 여기서 정한다")
	@PatchMapping("/me")
	public MyChannelResponse update(@CurrentMember MemberPrincipal me, @Valid @RequestBody ChannelUpdateRequest request) {
		return channelService.updateMine(me, request);
	}

	@Operation(summary = "스트림 키 재발급", description = "라이브 중이면 방송을 종료한 뒤 재발급. 옛 키는 즉시 무효")
	@PostMapping("/me/stream-key/rotate")
	public StreamKeyResponse rotateStreamKey(@CurrentMember MemberPrincipal me) {
		return channelService.rotateStreamKey(me);
	}

	@Operation(summary = "위젯 키 재발급", description = "옛 위젯 URL은 즉시 무효")
	@PostMapping("/me/widget-key/rotate")
	public WidgetKeyResponse rotateWidgetKey(@CurrentMember MemberPrincipal me) {
		return channelService.rotateWidgetKey(me);
	}
}
