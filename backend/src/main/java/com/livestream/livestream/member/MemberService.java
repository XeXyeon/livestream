package com.livestream.livestream.member;

import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberService {

	private final MemberRepository memberRepository;
	private final ChannelRepository channelRepository;

	@Transactional(readOnly = true)
	public MemberResponse getMe(Long memberId) {
		Member member = memberRepository.findById(memberId)
				.orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_FOUND));
		Channel channel = channelRepository.findByMemberId(memberId)
				.orElseThrow(() -> new ApiException(ErrorCode.CHANNEL_NOT_FOUND));
		return new MemberResponse(member.getId(), member.getEmail(), member.getNickname(), channel.getId(),
				member.getCreatedAt());
	}
}
