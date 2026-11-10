package com.livestream.livestream.chat;

import com.livestream.livestream.common.security.MemberPrincipal;
import java.security.Principal;

/** STOMP 세션의 로그인 회원. 이름은 회원 id(user 목적지 {@code /user/queue/errors} 가 이것으로 세션을 찾는다). */
public record StompPrincipal(MemberPrincipal member) implements Principal {

	@Override
	public String getName() {
		return String.valueOf(member.id());
	}

	/** 익명 세션이면 null. */
	public static MemberPrincipal memberOf(Principal principal) {
		return principal instanceof StompPrincipal p ? p.member() : null;
	}
}
