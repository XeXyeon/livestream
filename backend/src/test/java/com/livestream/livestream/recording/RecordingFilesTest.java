package com.livestream.livestream.recording;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordingFilesTest {

	@Test
	void MediaMTX_조각_경로를_녹화_디렉터리_기준_상대_경로로_바꾼다() {
		assertThat(RecordingFiles.toRelativeRawSegment("/recordings/raw/live/12/2026-11-10_07-00-00-123456.mp4", 12L))
				.isEqualTo("raw/live/12/2026-11-10_07-00-00-123456.mp4");
		// 마운트 위치가 달라도 마지막 /raw/live/ 부터 자른다
		assertThat(RecordingFiles.toRelativeRawSegment("/data/media/raw/live/7/a.mp4", 7L)).isEqualTo("raw/live/7/a.mp4");
		assertThat(RecordingFiles.toRelativeRawSegment("raw/live/7/a.mp4", 7L)).isEqualTo("raw/live/7/a.mp4");
	}

	@Test
	void 다른_채널_경로_이상한_문자_상위_경로는_거절한다() {
		assertThat(RecordingFiles.toRelativeRawSegment("/recordings/raw/live/12/a.mp4", 13L)).isNull();
		assertThat(RecordingFiles.toRelativeRawSegment("/recordings/raw/live/12/../../../etc/passwd", 12L)).isNull();
		assertThat(RecordingFiles.toRelativeRawSegment("/recordings/raw/live/12/a b.mp4", 12L)).isNull();
		assertThat(RecordingFiles.toRelativeRawSegment("/recordings/raw/live/12/a'.mp4", 12L)).isNull();
		assertThat(RecordingFiles.toRelativeRawSegment("/recordings/raw/live/12/sub/a.mp4", 12L)).isNull();
		assertThat(RecordingFiles.toRelativeRawSegment("/recordings/raw/live/12/..mp4", 12L)).isNull();
		assertThat(RecordingFiles.toRelativeRawSegment("/etc/passwd", 12L)).isNull();
		assertThat(RecordingFiles.toRelativeRawSegment(null, 12L)).isNull();
	}

	@Test
	void concat_목록은_작은따옴표를_이스케이프한다() {
		String list = RecordingMergeService.concatList(List.of(Path.of("/r/raw/a.mp4"), Path.of("/r/it's/b.mp4")));
		assertThat(list).isEqualTo("file '/r/raw/a.mp4'\nfile '/r/it'\\''s/b.mp4'\n");
	}

	@Test
	void 영상_경로는_공개_설정에_맞는_폴더로_바꾸고_영상_폴더_밖은_거절한다() {
		assertThat(RecordingFiles.videoPathFor("vod/a.mp4", Visibility.PRIVATE)).isEqualTo("private/a.mp4");
		assertThat(RecordingFiles.videoPathFor("private/a.mp4", Visibility.PUBLIC)).isEqualTo("vod/a.mp4");
		assertThat(RecordingFiles.videoPathFor("vod/a.mp4", Visibility.PUBLIC)).isEqualTo("vod/a.mp4");
		assertThat(RecordingFiles.counterpart("vod/a.mp4")).isEqualTo("private/a.mp4");
		assertThat(RecordingFiles.counterpart("private/a.mp4")).isEqualTo("vod/a.mp4");

		assertThat(RecordingFiles.counterpart(null)).isNull();
		assertThat(RecordingFiles.counterpart("raw/live/1/a.mp4")).isNull();
		assertThat(RecordingFiles.counterpart("vod/")).isNull();
		assertThat(RecordingFiles.counterpart("vod/..")).isNull();
		assertThat(RecordingFiles.counterpart("vod/../x.mp4")).isNull();
		assertThat(RecordingFiles.counterpart("private/sub/a.mp4")).isNull();
		assertThat(RecordingFiles.videoPathFor("thumbnails/1.jpg", Visibility.PUBLIC)).isNull();
	}
}
