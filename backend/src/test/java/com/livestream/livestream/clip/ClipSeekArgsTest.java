package com.livestream.livestream.clip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 클립 자르기 위치 인자(CLIP-1#4): 입력 앞에서는 대략, 출력 쪽에서 정확히 자른다. */
class ClipSeekArgsTest {

	private static final Path SRC = Path.of("/recordings/vod/a.mp4");

	@Test
	void 여유보다_뒤에서_시작하면_입력_앞에서_대략_찾고_나머지는_출력_쪽에서_자른다() {
		assertThat(ClipProcessingService.seekArgs(SRC, 20, 30))
				.containsExactly("-ss", "10", "-i", SRC.toString(), "-ss", "10", "-t", "30");
		assertThat(ClipProcessingService.seekArgs(SRC, 300, 15))
				.containsExactly("-ss", "290", "-i", SRC.toString(), "-ss", "10", "-t", "15");
	}

	@Test
	void 여유보다_앞에서_시작하면_입력_쪽_ss_없이_출력_쪽에서만_자른다() {
		assertThat(ClipProcessingService.seekArgs(SRC, 3, 30))
				.containsExactly("-i", SRC.toString(), "-ss", "3", "-t", "30");
		assertThat(ClipProcessingService.seekArgs(SRC, 10, 30))
				.containsExactly("-i", SRC.toString(), "-ss", "10", "-t", "30");
		assertThat(ClipProcessingService.seekArgs(SRC, 0, 30))
				.containsExactly("-i", SRC.toString(), "-ss", "0", "-t", "30");
	}
}
