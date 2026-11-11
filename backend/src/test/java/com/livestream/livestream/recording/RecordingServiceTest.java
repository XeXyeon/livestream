package com.livestream.livestream.recording;

import static org.assertj.core.api.Assertions.assertThat;

import com.livestream.livestream.common.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingServiceTest {

	@TempDir Path root;

	private RecordingService service(Path recordings, Path uploads) {
		AppProperties props = new AppProperties(null, null, null,
				new AppProperties.Recording(30, recordings.toString()), new AppProperties.Uploads(uploads.toString()),
				null, null, null, null, null);
		return new RecordingService(props);
	}

	@Test
	void vod와_썸네일을_각_기준_디렉터리에서_지운다() throws Exception {
		Path recordings = Files.createDirectories(root.resolve("media/vod")).getParent();
		Path uploads = Files.createDirectories(root.resolve("uploads/thumbnails")).getParent();
		Path vod = Files.writeString(recordings.resolve("vod/a.mp4"), "x");
		Path thumb = Files.writeString(uploads.resolve("thumbnails/7.jpg"), "x");

		service(recordings, uploads).deleteFiles("vod/a.mp4", "thumbnails/7.jpg");

		assertThat(vod).doesNotExist();
		assertThat(thumb).doesNotExist();
	}

	@Test
	void 영상은_다른_영상_폴더의_같은_파일_이름도_지운다() throws Exception {
		Path recordings = Files.createDirectories(root.resolve("media"));
		Path uploads = Files.createDirectories(root.resolve("uploads"));
		Files.createDirectories(recordings.resolve("vod"));
		Files.createDirectories(recordings.resolve("private"));
		Path moved = Files.writeString(recordings.resolve("private/b.mp4"), "x");
		Path other = Files.writeString(recordings.resolve("private/c.mp4"), "x");

		service(recordings, uploads).deleteFiles("vod/b.mp4", null);

		assertThat(moved).doesNotExist();
		assertThat(other).exists();
	}

	@Test
	void 없는_파일_null_기준_밖_경로는_조용히_넘어간다() throws Exception {
		Path recordings = Files.createDirectories(root.resolve("media"));
		Path uploads = Files.createDirectories(root.resolve("uploads"));
		Path outside = Files.writeString(root.resolve("secret.txt"), "x");

		RecordingService service = service(recordings, uploads);
		service.deleteFiles(null, "thumbnails/none.jpg");
		service.deleteFiles("../secret.txt", "../secret.txt");
		service.deleteFiles("", ".");

		assertThat(outside).exists();
		assertThat(recordings).exists();
		assertThat(uploads).exists();
	}
}
