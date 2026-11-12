package com.livestream.livestream.mediamtx;

import static org.assertj.core.api.Assertions.assertThat;

import com.livestream.livestream.broadcast.BroadcastSource;
import org.junit.jupiter.api.Test;

class MediaMtxPathsTest {

	@Test
	void 경로는_live_숫자만_채널로_본다() {
		assertThat(MediaMtxPaths.channelIdOf("live/12")).isEqualTo(12L);
		assertThat(MediaMtxPaths.channelIdOf("live/")).isNull();
		assertThat(MediaMtxPaths.channelIdOf("live/12/x")).isNull();
		assertThat(MediaMtxPaths.channelIdOf("/live/12")).isNull();
		assertThat(MediaMtxPaths.channelIdOf("live/1a")).isNull();
		assertThat(MediaMtxPaths.channelIdOf("live/9999999999999999999")).isNull(); // Long 범위 밖
		assertThat(MediaMtxPaths.channelIdOf(null)).isNull();
	}

	@Test
	void 쿼리_값은_디코드해서_첫_번째를_돌려준다() {
		assertThat(MediaMtxPaths.queryParam("user=obs&pass=abc", "pass")).isEqualTo("abc");
		assertThat(MediaMtxPaths.queryParam("pass=a%2Bb&pass=z", "pass")).isEqualTo("a+b");
		assertThat(MediaMtxPaths.queryParam("src", "src")).isEmpty();
		assertThat(MediaMtxPaths.queryParam("password=x", "pass")).isNull();
		assertThat(MediaMtxPaths.queryParam("pass=%zz", "pass")).isNull();
		assertThat(MediaMtxPaths.queryParam(null, "pass")).isNull();
	}

	@Test
	void 송출_수단_판정() {
		assertThat(MediaMtxPaths.sourceOf("rtmpConn", "src=mobile")).isEqualTo(BroadcastSource.STREAM_KEY);
		assertThat(MediaMtxPaths.sourceOf("srtConn", null)).isEqualTo(BroadcastSource.STREAM_KEY);
		assertThat(MediaMtxPaths.sourceOf("webRTCSession", "src=mobile")).isEqualTo(BroadcastSource.BROWSER_MOBILE);
		assertThat(MediaMtxPaths.sourceOf("webRTCSession", "src=obs")).isEqualTo(BroadcastSource.STREAM_KEY);
		assertThat(MediaMtxPaths.sourceOf("webRTCSession", "src=pc")).isEqualTo(BroadcastSource.BROWSER_PC);
		assertThat(MediaMtxPaths.sourceOf("webRTCSession", null)).isEqualTo(BroadcastSource.BROWSER_PC);
	}

	@Test
	void 안전한_쿼리_문자만_허용한다() {
		assertThat(MediaMtxPaths.isSafeQuery(null)).isTrue();
		assertThat(MediaMtxPaths.isSafeQuery("")).isTrue();
		assertThat(MediaMtxPaths.isSafeQuery("user=obs&pass=0123abcd&src=pc%20x+y-z_.~")).isTrue();
		for (String bad : new String[] {"a'b", "a\"b", "a\\b", "a$b", "a`b", "a b", "a;b", "a|b", "a\nb", "a(b)"}) {
			assertThat(MediaMtxPaths.isSafeQuery(bad)).as(bad).isFalse();
		}
	}
}
