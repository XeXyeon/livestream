package com.livestream.livestream.thumbnail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FE-3#1: 재인코딩 규칙. 마지막 테스트는 실제 ffmpeg 로 "녹화 중 해상도가 바뀐 H.264"를 만들어 고정 크기 하나로 다시 인코딩되는지
 * 본다(ffmpeg · libx264 가 없는 환경에서는 건너뛴다).
 */
class VideoTranscodeTest {

	@TempDir Path dir;

	@Test
	void ffprobe_출력은_경고가_섞여도_키로_읽고_패킷이_없으면_영상_없음() {
		var info = VideoTranscode.parse("""
				[h264 @ 0x1] sps_id 6 out of range
				width=720
				height=1280
				nb_read_packets=97
				duration=14.000000
				""");
		assertThat(info).isEqualTo(new VideoTranscode.VideoInfo(720, 1280, 97, 14.0));
		assertThat(info.hasVideo()).isTrue();

		// MediaMTX 드리프트 리셋으로 생긴 첫 조각: 영상 트랙은 있는데 패킷이 없다
		assertThat(VideoTranscode.parse("width=1920\nheight=1080\nnb_read_packets=N/A\nduration=5.0\n").hasVideo()).isFalse();
		// 영상 스트림 없음(소리만) · 길이 숫자만(가짜 ffprobe) · null
		assertThat(VideoTranscode.parse("duration=12.6\n").hasVideo()).isFalse();
		assertThat(VideoTranscode.parse("12.6\n")).isEqualTo(VideoTranscode.VideoInfo.NONE);
		assertThat(VideoTranscode.parse(null)).isEqualTo(VideoTranscode.VideoInfo.NONE);
	}

	@Test
	void 출력_크기는_긴_변_1920_이하_짝수이고_키우지_않는다() {
		assertThat(VideoTranscode.targetSize(2560, 1290)).containsExactly(1920, 966);
		assertThat(VideoTranscode.targetSize(720, 1280)).containsExactly(720, 1280);
		assertThat(VideoTranscode.targetSize(1081, 2400)).containsExactly(864, 1920);
		assertThat(VideoTranscode.targetSize(641, 361)).containsExactly(640, 360);
	}

	@Test
	void 인코딩_옵션은_고정_크기로_맞추고_오디오는_복사한다() {
		List<String> args = VideoTranscode.encodeArgs(1280, 720, VideoTranscode.PRESET_FINAL);
		assertThat(String.join(" ", args)).contains(
				"-vf scale=w=1280:h=720:force_original_aspect_ratio=decrease:force_divisible_by=2,pad=1280:720:",
				"-c:v libx264 -preset veryfast", "-c:a copy", "comment=" + VideoTranscode.TAG);
	}

	@Test
	void 시간_초과는_영상_길이의_4배와_기본값_중_큰_쪽() {
		assertThat(VideoTranscode.timeoutFor(3600.0, Duration.ofMinutes(10))).isEqualTo(Duration.ofHours(4));
		assertThat(VideoTranscode.timeoutFor(30.0, Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
		assertThat(VideoTranscode.timeoutFor(null, Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
	}

	@Test
	void 프레임_크기_줄은_경고와_빈_칸을_견딘다() {
		assertThat(VideoTranscode.frameSize("1280,720")).containsExactly(1280, 720);
		assertThat(VideoTranscode.frameSize("960,540,")).containsExactly(960, 540);
		assertThat(VideoTranscode.frameSize("[h264 @ 0x1] sps_id 6 out of range")).isNull();
		assertThat(VideoTranscode.frameSize("0,0")).isNull();
	}

	@Test
	void 확인_시간_초과는_파일_크기에_맞춰_늘고_실패는_null() throws Exception {
		Path small = Files.write(dir.resolve("small.mp4"), new byte[10]);
		assertThat(VideoTranscode.probeTimeout(small, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(30));
		Path big = dir.resolve("big.mp4");
		try (var f = new java.io.RandomAccessFile(big.toFile(), "rw")) {
			f.setLength(4L * 1024 * 1024 * 1024); // 성긴 파일 4GB
		}
		assertThat(VideoTranscode.probeTimeout(big, Duration.ofSeconds(30))).isEqualTo(Duration.ofSeconds(204));
		FfmpegRunner failing = new FfmpegRunner() {
			public Result ffmpeg(List<String> args, Duration timeout) {
				throw new UnsupportedOperationException();
			}

			public Result ffprobe(List<String> args, Duration timeout) {
				return new Result(-1, "", true);
			}
		};
		assertThat(VideoTranscode.probe(failing, small, Duration.ofSeconds(1))).isNull();
	}

	@Test
	void 실제_ffmpeg_해상도가_바뀐_녹화를_한_크기로_다시_인코딩한다() throws Exception {
		ProcessFfmpegRunner runner = new ProcessFfmpegRunner("ffmpeg", "ffprobe");
		assumeThat(runner.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "nullsrc=s=64x64:d=0.1",
				"-c:v", "libx264", "-f", "null", "-"), Duration.ofSeconds(20)).success()).as("ffmpeg + libx264 필요").isTrue();

		// 1280x720 2초 + 720x1280 2초 H.264 를 annexb 로 이어 붙인다(프레임 안의 SPS 가 중간에 바뀐다) → mp4 + opus
		Path a = dir.resolve("a.h264");
		Path b = dir.resolve("b.h264");
		Path ab = dir.resolve("ab.h264");
		Path src = dir.resolve("src.mp4");
		Path out = dir.resolve("out.mp4");
		ok(runner, "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30", "-t", "2", "-c:v", "libx264", "-f", "h264", a.toString());
		ok(runner, "-f", "lavfi", "-i", "testsrc2=size=720x1280:rate=30", "-t", "2", "-c:v", "libx264", "-f", "h264", b.toString());
		Files.write(ab, concat(Files.readAllBytes(a), Files.readAllBytes(b)));
		ok(runner, "-r", "30", "-i", ab.toString(), "-f", "lavfi", "-i", "sine=sample_rate=48000", "-t", "4",
				"-c:v", "copy", "-c:a", "aac", "-shortest", src.toString());

		var info = VideoTranscode.probe(runner, src, Duration.ofSeconds(30));
		assertThat(info.hasVideo()).isTrue();
		assertThat(info.width()).isEqualTo(1280); // 첫 프레임 크기
		// 앞은 1280x720, 가운데(2초)는 720x1280: 면적이 같으면 먼저 본 크기를 쓴다
		int[] source = VideoTranscode.sourceSize(runner, src, info, Duration.ofSeconds(30));
		assertThat(source).containsExactly(1280, 720);
		int[] size = VideoTranscode.targetSize(source[0], source[1]);
		List<String> args = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y", "-i", src.toString()));
		args.addAll(VideoTranscode.encodeArgs(size[0], size[1], VideoTranscode.PRESET_PREVIEW));
		args.add(out.toString());
		assertThat(runner.ffmpeg(args, Duration.ofMinutes(1)).success()).isTrue();

		// 모든 프레임이 첫 크기 하나로 디코딩된다
		var frames = runner.ffprobe(List.of("-v", "error", "-select_streams", "v:0", "-show_entries", "frame=width,height",
				"-of", "csv=p=0", out.toString()), Duration.ofSeconds(30));
		// csv 줄 끝에 빈 side data 칸(",")이 붙기도 한다
		List<String> lines = frames.output().strip().lines().map(l -> l.replaceAll(",+$", "")).toList();
		assertThat(lines.stream().distinct().toList()).containsExactly("1280,720");
		// 뒤쪽(세로 화면) 2초도 버리지 않고 디코딩됐다(-shortest 로 끝 몇 프레임은 잘릴 수 있다)
		assertThat(lines.size()).isGreaterThan(110);
		assertThat(runner.ffprobe(List.of("-v", "error", "-show_entries", "format_tags=comment", "-of", "csv=p=0",
				out.toString()), Duration.ofSeconds(10)).output()).contains(VideoTranscode.TAG);
	}

	@Test
	void 처음엔_작게_보내다_올린_송출은_가운데_프레임의_큰_크기를_쓴다() throws Exception {
		ProcessFfmpegRunner runner = new ProcessFfmpegRunner("ffmpeg", "ffprobe");
		assumeThat(runner.ffmpeg(List.of("-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "nullsrc=s=64x64:d=0.1",
				"-c:v", "libx264", "-f", "null", "-"), Duration.ofSeconds(20)).success()).as("ffmpeg + libx264 필요").isTrue();
		Path a = dir.resolve("a.h264");
		Path b = dir.resolve("b.h264");
		Path ab = dir.resolve("ab.h264");
		Path src = dir.resolve("ramp.mp4");
		ok(runner, "-f", "lavfi", "-i", "testsrc2=size=960x540:rate=30", "-t", "2", "-c:v", "libx264", "-f", "h264", a.toString());
		ok(runner, "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30", "-t", "6", "-c:v", "libx264", "-f", "h264", b.toString());
		Files.write(ab, concat(Files.readAllBytes(a), Files.readAllBytes(b)));
		ok(runner, "-r", "30", "-i", ab.toString(), "-c:v", "copy", src.toString());

		var info = VideoTranscode.probe(runner, src, Duration.ofSeconds(30));
		assertThat(info.width()).isEqualTo(960);
		assertThat(VideoTranscode.sourceSize(runner, src, info, Duration.ofSeconds(30))).containsExactly(1280, 720);
	}

	private static void ok(ProcessFfmpegRunner runner, String... args) {
		List<String> all = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y"));
		all.addAll(List.of(args));
		FfmpegRunner.Result r = runner.ffmpeg(all, Duration.ofMinutes(1));
		assertThat(r.success()).as(r.describe()).isTrue();
	}

	private static byte[] concat(byte[] x, byte[] y) {
		byte[] z = new byte[x.length + y.length];
		System.arraycopy(x, 0, z, 0, x.length);
		System.arraycopy(y, 0, z, x.length, y.length);
		return z;
	}
}
