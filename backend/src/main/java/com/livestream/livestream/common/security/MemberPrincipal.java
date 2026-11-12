package com.livestream.livestream.common.security;

/** Access JWT에서 복원한 로그인 회원 정보. */
public record MemberPrincipal(Long id, String nickname, Long channelId) {
}
