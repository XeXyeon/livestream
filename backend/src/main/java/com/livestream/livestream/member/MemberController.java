package com.livestream.livestream.member;

import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.CurrentMember;
import com.livestream.livestream.common.security.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Member", description = "내 정보")
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService memberService;

	@Operation(summary = "내 정보", description = "로그인 필요(Authorization: Bearer)")
	@GetMapping("/me")
	public MemberResponse me(@CurrentMember MemberPrincipal principal) {
		if (principal == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		return memberService.getMe(principal.id());
	}
}
