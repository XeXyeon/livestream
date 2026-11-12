package com.livestream.livestream.thumbnail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link ProcessBuilder} 로 ffmpeg · ffprobe 를 띄운다. PATH 의 실행 파일을 쓴다(backend 이미지는 apk ffmpeg).
 * 출력은 별도 스레드가 끝까지 읽어(파이프가 차서 멈추는 것 방지) 끝부분만 남긴다.
 */
@Slf4j
@Component
public class ProcessFfmpegRunner implements FfmpegRunner {

	private final String ffmpegBin;
	private final String ffprobeBin;

	public ProcessFfmpegRunner() {
		this("ffmpeg", "ffprobe");
	}

	/** 테스트용: 실행 파일 이름을 바꾼다. */
	ProcessFfmpegRunner(String ffmpegBin, String ffprobeBin) {
		this.ffmpegBin = ffmpegBin;
		this.ffprobeBin = ffprobeBin;
	}

	@Override
	public Result ffmpeg(List<String> args, Duration timeout) {
		return run(ffmpegBin, args, timeout);
	}

	@Override
	public Result ffprobe(List<String> args, Duration timeout) {
		return run(ffprobeBin, args, timeout);
	}

	private Result run(String bin, List<String> args, Duration timeout) {
		List<String> command = new ArrayList<>(args.size() + 1);
		command.add(bin);
		command.addAll(args);
		Process process;
		try {
			process = new ProcessBuilder(command).redirectErrorStream(true).start();
		} catch (IOException e) {
			return new Result(-1, "실행 실패: " + e.getMessage(), false);
		}
		try {
			process.getOutputStream().close(); // ffmpeg 가 stdin 을 기다리지 않게
		} catch (IOException ignored) {
			// 무시
		}
		TailBuffer tail = new TailBuffer();
		Thread reader = Thread.ofVirtual().start(() -> tail.drain(process.getInputStream()));
		try {
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
				reader.join(Duration.ofSeconds(1));
				return new Result(-1, tail.text(), true);
			}
			reader.join(Duration.ofSeconds(5));
			return new Result(process.exitValue(), tail.text(), false);
		} catch (InterruptedException e) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			return new Result(-1, "중단됨", false);
		}
	}

	/** 출력 끝부분만 남기는 버퍼. */
	private static final class TailBuffer {

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		void drain(InputStream in) {
			byte[] chunk = new byte[8192];
			try (in) {
				int n;
				while ((n = in.read(chunk)) != -1) {
					synchronized (buffer) {
						buffer.write(chunk, 0, n);
						if (buffer.size() > Result.OUTPUT_LIMIT * 4) {
							byte[] all = buffer.toByteArray();
							buffer.reset();
							buffer.write(all, all.length - Result.OUTPUT_LIMIT * 2, Result.OUTPUT_LIMIT * 2);
						}
					}
				}
			} catch (IOException ignored) {
				// 프로세스가 강제 종료되면 스트림이 닫힌다.
			}
		}

		String text() {
			String s;
			synchronized (buffer) {
				s = buffer.toString(StandardCharsets.UTF_8);
			}
			return s.length() > Result.OUTPUT_LIMIT ? s.substring(s.length() - Result.OUTPUT_LIMIT) : s;
		}
	}
}
