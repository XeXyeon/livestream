package com.livestream.livestream.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** ffprobe 길이 출력에 stderr 경고가 섞여도 길이를 읽는다(운영 브라우저 녹화에서 duration 이 null 로 남던 문제). */
class FfmpegResultDurationTest {

	private static FfmpegRunner.Result out(String output) {
		return new FfmpegRunner.Result(0, output, false);
	}

	@Test
	void 숫자_한_줄이면_그대로() {
		assertThat(out("43.260000\n").durationSeconds()).isEqualTo(43.26);
	}

	@Test
	void 앞에_디코더_경고가_섞여도_마지막_숫자_줄을_쓴다() {
		String output = "[h264 @ 0x7fba8641e980] sps_id 6 out of range\n    Last message repeated 1 times\n"
				+ "[h264 @ 0x7fba84e388c0] sps_id 6 out of range\n43.260000\n";
		assertThat(out(output).durationSeconds()).isEqualTo(43.26);
	}

	@Test
	void 숫자가_없거나_NA면_null() {
		assertThat(out("N/A\n").durationSeconds()).isNull();
		assertThat(out("").durationSeconds()).isNull();
		assertThat(out(null).durationSeconds()).isNull();
	}
}
