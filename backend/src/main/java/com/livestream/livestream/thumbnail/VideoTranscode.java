package com.livestream.livestream.thumbnail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 다시보기 · 클립 영상 재인코딩 규칙 (FE-3#1, DESIGN §4.10 · §4.18).
 * <p>
 * 브라우저 송출(WebRTC) 녹화를 {@code -c copy} 로 그대로 두면 브라우저 {@code <video>} 가 디코딩하지 못하는 두 경우가 있다.
 * <ul>
 *   <li>MediaMTX fMP4 녹화기는 첫 part 를 닫을 때까지 SPS/PPS 를 못 받으면 init(avcC)에 기본값(1920×1080 baseline,
 *       PPS {@code 08 06 07 08})을 쓰고 이후 바꾸지 않는다. ffprobe {@code sps_id out of range} 가 이것이다.</li>
 *   <li>송출 중 해상도가 바뀌면(혼잡 적응 · 탭 크기 · 휴대폰 회전) 새 SPS 가 생기는데 mp4 init 에는 첫 값만 남는다.</li>
 * </ul>
 * 그래서 영상은 libx264 로 다시 인코딩한다. 디코더는 프레임 안의 SPS/PPS 를 따르므로 init 이 틀려도 읽을 수 있고,
 * 출력은 크기가 고정된(긴 변 {@value #MAX_LONG_SIDE} 이하, 짝수) H.264 하나라 init 이 늘 맞는다. 입력 해상도가 바뀌면 비율을
 * 지켜 줄이고 남는 곳은 검은 띠로 채운다. 오디오(Opus · AAC)는 그대로 복사한다.
 */
public final class VideoTranscode {

	public static final int MAX_LONG_SIDE = 1920;
	/** 재인코딩한 파일 표시(백필 스크립트가 건너뛴다, infra/scripts/reencode-media.sh). */
	public static final String TAG = "gridlive-h264-v1";
	/** 다시보기 · 클립. */
	public static final String PRESET_FINAL = "veryfast";
	/** 라이브 클립 초안(고르는 동안만 쓰고 10분 뒤 지운다): 빨리 만드는 쪽. */
	public static final String PRESET_PREVIEW = "ultrafast";
	/** 운영 VM 은 2 vCPU 다. 송출 · 시청을 함께 돌리므로 인코더는 2스레드까지만. */
	static final String THREADS = "2";

	private VideoTranscode() {
	}

	/**
	 * 첫 영상 스트림의 크기 · 패킷 수와 파일 길이.
	 *
	 * @param width    0 이면 영상 스트림 없음
	 * @param packets  영상 패킷 수(셀 수 없으면 0)
	 * @param duration 파일 길이(초), 모르면 null
	 */
	public record VideoInfo(int width, int height, long packets, Double duration) {

		public static final VideoInfo NONE = new VideoInfo(0, 0, 0, null);

		/** 실제 영상 프레임이 있는가. MediaMTX 드리프트 리셋으로 생긴 첫 조각은 영상 트랙만 있고 패킷이 없다. */
		public boolean hasVideo() {
			return width > 0 && height > 0 && packets > 0;
		}
	}

	/** {@code ffprobe} 인자. 출력은 {@code key=value} 줄(영상이 없으면 duration 만). */
	public static List<String> probeArgs(Path file) {
		return List.of("-v", "error", "-select_streams", "v:0", "-count_packets",
				"-show_entries", "stream=width,height,nb_read_packets:format=duration",
				"-of", "default=noprint_wrappers=1", file.toString());
	}

	/**
	 * 영상 확인. {@code -count_packets} 는 파일 전체를 읽으므로(디코딩은 하지 않는다) 시간 초과는 파일 크기에 맞춰 늘린다
	 * ({@link #probeTimeout}). <b>실패(시간 초과 · 오류)면 null</b> — "영상 없음"과 구분해야 한다. 실패를 영상 없음으로
	 * 읽으면 병합이 정상 조각을 빼고 원본까지 지운다.
	 */
	public static VideoInfo probe(FfmpegRunner runner, Path file, Duration base) {
		FfmpegRunner.Result r = runner.ffprobe(probeArgs(file), probeTimeout(file, base));
		return r.success() ? parse(r.output()) : null;
	}

	/** {@code max(base, 파일 크기 / 20MB/s)}. MediaMTX 조각은 최대 6시간(수 GB)이다. */
	static Duration probeTimeout(Path file, Duration base) {
		long size;
		try {
			size = Files.size(file);
		} catch (IOException e) {
			return base;
		}
		Duration scaled = Duration.ofSeconds(size / (20L * 1024 * 1024));
		return scaled.compareTo(base) > 0 ? scaled : base;
	}

	/** {@link #probeArgs} 출력 해석. 섞여 든 stderr 경고 줄 · 모르는 줄은 건너뛴다. */
	public static VideoInfo parse(String output) {
		if (output == null) {
			return VideoInfo.NONE;
		}
		int width = 0;
		int height = 0;
		long packets = 0;
		Double duration = null;
		for (String line : output.split("\\R")) {
			int eq = line.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = line.substring(0, eq).strip();
			String value = line.substring(eq + 1).strip();
			try {
				switch (key) {
					case "width" -> width = Integer.parseInt(value);
					case "height" -> height = Integer.parseInt(value);
					case "nb_read_packets" -> packets = Long.parseLong(value);
					case "duration" -> {
						double d = Double.parseDouble(value);
						duration = Double.isFinite(d) && d >= 0 ? d : null;
					}
					default -> {
						// 다른 키는 쓰지 않는다
					}
				}
			} catch (NumberFormatException ignored) {
				// N/A 등
			}
		}
		return new VideoInfo(Math.max(width, 0), Math.max(height, 0), Math.max(packets, 0), duration);
	}

	/**
	 * 출력 크기를 정할 원본 크기. <b>실제로 디코딩한 프레임 크기</b>를 쓴다: 앞쪽 영상 패킷 30개와 가운데 30개를 디코딩해 면적이
	 * 가장 큰 프레임 크기. ffprobe 스트림 크기는 믿지 않는다 — MediaMTX 기본값 avcC(1920×1080)인 조각에서 영상이 늦게 시작하면
	 * 분석 구간(5초)이 소리로 채워져 기본값 크기가 나온다(로컬 실측: 1280×720 송출이 1920×1080 으로 키워짐). 처음엔 작게 보내다
	 * 올리는 WebRTC 송출(960×540 → 1280×720)도 가운데 프레임으로 잡는다. 프레임을 하나도 디코딩하지 못하면 스트림 크기.
	 */
	public static int[] sourceSize(FfmpegRunner runner, Path file, VideoInfo info, Duration timeout) {
		int[] best = null;
		List<String> intervals = new ArrayList<>(List.of("%+#30"));
		if (info.duration() != null && info.duration() >= 4) {
			intervals.add((long) Math.floor(info.duration() / 2) + "%+#30");
		}
		for (String interval : intervals) {
			FfmpegRunner.Result r = runner.ffprobe(List.of("-v", "error", "-select_streams", "v:0", "-read_intervals", interval,
					"-show_entries", "frame=width,height", "-of", "csv=p=0", file.toString()), timeout);
			if (!r.success() || r.output() == null) {
				continue;
			}
			for (String line : r.output().split("\\R")) {
				int[] wh = frameSize(line);
				if (wh != null && (best == null || (long) wh[0] * wh[1] > (long) best[0] * best[1])) {
					best = wh;
				}
			}
		}
		return best != null ? best : new int[] {info.width(), info.height()};
	}

	/** {@code 1280,720} 또는 {@code 1280,720,}(빈 side data 칸). 경고 줄 등 다른 줄은 null. */
	static int[] frameSize(String line) {
		String[] parts = line.strip().split(",");
		if (parts.length < 2) {
			return null;
		}
		try {
			int w = Integer.parseInt(parts[0].strip());
			int h = Integer.parseInt(parts[1].strip());
			return w > 0 && h > 0 ? new int[] {w, h} : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 출력 크기: 비율을 지켜 긴 변을 {@value #MAX_LONG_SIDE} 이하로, 양쪽 짝수(최소 2). 키우지는 않는다. */
	public static int[] targetSize(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("영상 크기가 없습니다");
		}
		double scale = Math.min(1.0, (double) MAX_LONG_SIDE / Math.max(width, height));
		return new int[] {even(width * scale), even(height * scale)};
	}

	private static int even(double v) {
		return Math.max(2, ((int) Math.floor(v)) & ~1);
	}

	/**
	 * 영상 재인코딩 · 오디오 복사 출력 옵션({@code -i} 뒤, 출력 파일 앞). 크기는 {@link #targetSize} 로 정한 고정값이라
	 * 중간에 입력 해상도가 바뀌어도 출력 크기는 그대로다. 키프레임은 2초마다(다시보기 탐색 · 클립 자르기).
	 */
	public static List<String> encodeArgs(int width, int height, String preset) {
		String vf = "scale=w=" + width + ":h=" + height + ":force_original_aspect_ratio=decrease:force_divisible_by=2,"
				+ "pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2,setsar=1";
		List<String> args = new ArrayList<>(List.of(
				"-map", "0:v:0", "-map", "0:a?",
				"-vf", vf,
				"-c:v", "libx264", "-preset", preset, "-crf", "23", "-maxrate", "6M", "-bufsize", "12M",
				"-pix_fmt", "yuv420p", "-force_key_frames", "expr:gte(t,n_forced*2)", "-threads", THREADS,
				"-c:a", "copy",
				"-metadata", "comment=" + TAG));
		return args;
	}

	/**
	 * 재인코딩 시간 초과: 기본값과 "영상 길이 × 4" 가운데 큰 쪽. 운영 VM(2 vCPU)에서 1080p veryfast 는 실시간의 1~1.5배쯤 걸린다
	 * (Apple M4 2스레드 실측 0.54배, 노이즈가 많은 원본 기준).
	 */
	public static Duration timeoutFor(Double seconds, Duration base) {
		if (seconds == null || seconds <= 0) {
			return base;
		}
		Duration scaled = Duration.ofSeconds((long) Math.ceil(seconds * 4));
		return scaled.compareTo(base) > 0 ? scaled : base;
	}
}
