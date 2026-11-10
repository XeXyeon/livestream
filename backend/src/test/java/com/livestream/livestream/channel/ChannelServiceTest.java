package com.livestream.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.member.Member;
import com.livestream.livestream.post.PostRepository;
import com.livestream.livestream.recording.RecordingRepository;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 훅 구현(BC-1 전)이 없는 지금의 운영 경로와 public-url 가공 단위 테스트. */
class ChannelServiceTest {

	@SuppressWarnings("unchecked")
	private ChannelService service(String publicUrl, Channel channel) {
		ChannelRepository repo = mock(ChannelRepository.class);
		when(repo.findByMemberIdForUpdate(1L)).thenReturn(Optional.of(channel));
		when(repo.findByMemberId(1L)).thenReturn(Optional.of(channel));
		ObjectProvider<LiveBroadcastTerminator> none = mock(ObjectProvider.class);
		when(none.orderedStream()).thenReturn(Stream.empty());
		AppProperties props = new AppProperties(null, null, null, null, null, null, null, null, null, publicUrl);
		return new ChannelService(repo, props, none, channelId -> Optional.empty(), mock(PostRepository.class),
				mock(RecordingRepository.class));
	}

	private Channel channel() {
		Member member = mock(Member.class);
		when(member.getId()).thenReturn(1L);
		when(member.getNickname()).thenReturn("닉");
		Channel channel = Channel.createFor(member);
		return spyWithId(channel);
	}

	private Channel spyWithId(Channel channel) {
		Channel spy = org.mockito.Mockito.spy(channel);
		when(spy.getId()).thenReturn(7L);
		return spy;
	}

	private static final MemberPrincipal ME = new MemberPrincipal(1L, "닉", 7L);

	@Test
	void 종료_훅_구현이_없어도_재발급은_된다() {
		Channel channel = channel();
		String old = channel.getStreamKey();

		String fresh = service("http://localhost", channel).rotateStreamKey(ME).streamKey();

		assertThat(fresh).matches("[0-9a-f]{32}").isNotEqualTo(old);
		assertThat(channel.getStreamKey()).isEqualTo(fresh);
	}

	@Test
	void public_url_끝_슬래시는_제거하고_RTMP는_호스트만_쓴다() {
		var res = service("https://stream.example.com:8443/", channel()).getMine(ME);

		assertThat(res.publish().whipUrl()).isEqualTo("https://stream.example.com:8443/live/7/whip");
		assertThat(res.publish().rtmpUrl()).isEqualTo("rtmp://stream.example.com/live");
		assertThat(res.widgetUrl()).startsWith("https://stream.example.com:8443/widget/");
	}
}
