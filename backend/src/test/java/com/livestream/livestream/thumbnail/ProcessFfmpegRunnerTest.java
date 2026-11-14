package com.livestream.livestream.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 실행 파일 이름을 echo · sleep · sh 로 바꿔 프로세스 실행 · 시간 초과 · 출력 수집을 확인한다. */
class ProcessFfmpegRunnerTest {

	@Test
	void 성공하면_종료코드_0과_출력을_돌려준다() {
		var result = new ProcessFfmpegRunner("echo", "echo").ffmpeg(List.of("hello", "world"), Duration.ofSeconds(5));
		assertThat(result.success()).isTrue();
		assertThat(result.output()).isEqualTo("hello world\n");
	}

	@Test
	void 인자는_셸을_거치지_않는다() {
		var result = new ProcessFfmpegRunner("echo", "echo").ffprobe(List.of("a; rm -rf /tmp/x", "$HOME"), Duration.ofSeconds(5));
		assertThat(result.output()).isEqualTo("a; rm -rf /tmp/x $HOME\n");
	}

	@Test
	void 시간을_넘기면_강제_종료하고_timedOut() {
		long start = System.nanoTime();
		var result = new ProcessFfmpegRunner("sleep", "sleep").ffmpeg(List.of("10"), Duration.ofMillis(300));
		assertThat(result.timedOut()).isTrue();
		assertThat(result.success()).isFalse();
		assertThat(result.describe()).isEqualTo("TIMEOUT");
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
	}

	@Test
	void 실패_종료코드와_stderr_끝부분() {
		var result = new ProcessFfmpegRunner("sh", "sh").ffmpeg(
				List.of("-c", "head -c 20000 /dev/zero | tr '\\0' x; echo; echo boom >&2; exit 3"), Duration.ofSeconds(5));
		assertThat(result.exitCode()).isEqualTo(3);
		assertThat(result.output()).hasSizeLessThanOrEqualTo(FfmpegRunner.Result.OUTPUT_LIMIT).endsWith("boom\n");
		assertThat(result.describe()).startsWith("exit=3 ").endsWith("boom");
	}

	@Test
	void 없는_실행_파일은_실패로_돌려준다() {
		var result = new ProcessFfmpegRunner("no-such-binary-xyz", "x").ffmpeg(List.of(), Duration.ofSeconds(1));
		assertThat(result.success()).isFalse();
		assertThat(result.exitCode()).isEqualTo(-1);
	}
}
