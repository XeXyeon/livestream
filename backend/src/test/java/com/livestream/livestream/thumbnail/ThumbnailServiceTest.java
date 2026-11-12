package com.livestream.livestream.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.LiveBroadcastRef;
import com.livestream.livestream.common.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** BC-3: 캡처 결과에 따른 파일 · DB 경로 갱신 로직 (FfmpegRunner 는 가짜, DESIGN §4.17). */
class ThumbnailServiceTest {

	@TempDir Path uploads;

	private BroadcastRepository repo;
	private FakeRunner runner;
	private ThumbnailService service;

	/** 임시 출력 파일(마지막 인자)에 내용을 쓰거나 실패를 흉내 낸다. */
	static class FakeRunner implements FfmpegRunner {
		final List<List<String>> calls = new ArrayList<>();
		Result next = new Result(0, "", false);
		boolean writeFile = true;

		@Override
		public Result ffmpeg(List<String> args, Duration timeout) {
			calls.add(args);
			if (writeFile && next.success()) {
				try {
					Files.writeString(Path.of(args.get(args.size() - 1)), "jpeg");
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			}
			return next;
		}

		@Override
		public Result ffprobe(List<String> args, Duration timeout) {
			throw new UnsupportedOperationException();
		}
	}

	@BeforeEach
	void setUp() {
		repo = mock(BroadcastRepository.class);
		when(repo.isLive(anyLong())).thenReturn(true);
		runner = new FakeRunner();
		AppProperties props = new AppProperties(null, null, null, null, new AppProperties.Uploads(uploads.toString()),
				new AppProperties.Mediamtx("http://mediamtx:9997", "rtsp://mediamtx:8554/", "t"), null, null, null, null);
		service = new ThumbnailService(repo, runner, props);
	}

	@Test
	void 성공하면_thumbnails_id_jpg를_만들고_DB_경로를_채운다() {
		assertThat(service.capture(7L, 3L)).isTrue();

		assertThat(uploads.resolve("thumbnails/7.jpg")).exists().hasContent("jpeg");
		assertThat(uploads.resolve("thumbnails/7.tmp.jpg")).doesNotExist();
		verify(repo).assignThumbnailPathIfAbsent(7L, "thumbnails/7.jpg");
		List<String> args = runner.calls.getFirst();
		assertThat(args).containsSequence("-rtsp_transport", "tcp", "-i", "rtsp://mediamtx:8554/live/3");
		assertThat(args).containsSequence("-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "4", "-y");
	}

	@Test
	void 파일은_매번_덮어쓰고_경로_갱신_요청은_그대로_보낸다_최초_1회는_리포지터리가_보장() throws Exception {
		service.capture(7L, 3L);
		Files.writeString(uploads.resolve("thumbnails/7.jpg"), "old");
		service.capture(7L, 3L);

		assertThat(uploads.resolve("thumbnails/7.jpg")).hasContent("jpeg");
	}

	@Test
	void ffmpeg가_실패하거나_타임아웃이면_아무것도_바꾸지_않는다() {
		runner.next = new FfmpegRunner.Result(1, "Connection refused", false);
		assertThat(service.capture(7L, 3L)).isFalse();
		runner.next = new FfmpegRunner.Result(-1, "", true);
		assertThat(service.capture(7L, 3L)).isFalse();

		assertThat(uploads.resolve("thumbnails/7.jpg")).doesNotExist();
		verify(repo, never()).assignThumbnailPathIfAbsent(anyLong(), anyString());
	}

	@Test
	void 성공_코드여도_파일이_없거나_비어_있으면_실패로_본다() throws Exception {
		runner.writeFile = false;
		assertThat(service.capture(7L, 3L)).isFalse();
		verify(repo, never()).assignThumbnailPathIfAbsent(anyLong(), anyString());
	}

	@Test
	void 캡처하는_동안_방송이_끝났으면_파일을_남기지_않는다() {
		when(repo.isLive(7L)).thenReturn(false);

		assertThat(service.capture(7L, 3L)).isFalse();

		assertThat(uploads.resolve("thumbnails/7.jpg")).doesNotExist();
		assertThat(uploads.resolve("thumbnails/7.tmp.jpg")).doesNotExist();
		verify(repo, never()).assignThumbnailPathIfAbsent(anyLong(), anyString());
	}

	@Test
	void 한_방송이_예외를_던져도_다음_방송을_계속_캡처한다() {
		when(repo.findLiveRefs()).thenReturn(List.of(new LiveBroadcastRef(1L, 10L), new LiveBroadcastRef(2L, 20L)));
		when(repo.isLive(1L)).thenThrow(new IllegalStateException("db down"));

		service.captureAllLive();

		assertThat(runner.calls).hasSize(2);
		assertThat(uploads.resolve("thumbnails/2.jpg")).exists();
		verify(repo).assignThumbnailPathIfAbsent(2L, "thumbnails/2.jpg");
	}
}
