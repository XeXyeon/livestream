package com.livestream.livestream.clip;

/** 클립을 고칠 수 있는 두 회원(§4.8): 만든 회원 · 채널 주인. 잠금 전 권한 확인용 프로젝션. */
public record ClipManagers(Long creatorId, Long channelOwnerId) {

	boolean includes(Long memberId) {
		return memberId != null && (memberId.equals(creatorId) || memberId.equals(channelOwnerId));
	}
}
