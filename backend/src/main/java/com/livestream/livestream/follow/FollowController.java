package com.livestream.livestream.follow;

import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.follow.dto.FollowStatusResponse;
import com.livestream.livestream.follow.dto.FollowedChannelResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Follow", description = "채널 팔로우")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FollowController {

	private final FollowService followService;

	@Operation(summary = "채널 팔로우", description = "로그인 필요. 멱등: 이미 팔로우 중이어도 200. 자기 채널은 400")
	@PostMapping("/channels/{id}/follow")
	public FollowStatusResponse follow(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return followService.follow(me, id);
	}

	@Operation(summary = "채널 팔로우 해제", description = "로그인 필요. 멱등: 팔로우하지 않았어도 200")
	@DeleteMapping("/channels/{id}/follow")
	public FollowStatusResponse unfollow(@CurrentMember MemberPrincipal me, @PathVariable Long id) {
		return followService.unfollow(me, id);
	}

	@Operation(summary = "내 팔로우 목록", description = "로그인 필요. 라이브 먼저(시청자 수 순), 그다음 팔로우한 순(최신 먼저)")
	@GetMapping("/members/me/follows")
	public List<FollowedChannelResponse> myFollows(@CurrentMember MemberPrincipal me) {
		return followService.myFollows(me);
	}
}
