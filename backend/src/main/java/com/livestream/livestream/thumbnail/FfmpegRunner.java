package com.livestream.livestream.thumbnail;

import java.time.Duration;
import java.util.List;

/**
 * ffmpeg · ffprobe 실행 (DESIGN §4.11 · §4.10, 썸네일 캡처 BC-3 · 녹화 병합 REC-1 공용).
 * 셸을 거치지 않고 인자 목록 그대로 프로세스를 띄운다. 테스트에서는 가짜 구현으로 바꾼다(§4.17).
 */
public interface FfmpegRunner {

	/** {@code ffmpeg <args…>}. 시간 안에 끝나지 않으면 프로세스를 강제로 끝내고 {@code timedOut=true}. */
	Result ffmpeg(List<String> args, Duration timeout);

	/** {@code ffprobe <args…>}. */
	Result ffprobe(List<String> args, Duration timeout);

	/**
	 * @param exitCode 종료 코드(시간 초과 · 실행 실패면 -1)
	 * @param output   stdout + stderr 의 끝부분(최대 {@link #OUTPUT_LIMIT}자)
	 * @param timedOut 시간 초과로 강제 종료했으면 true
	 */
	record Result(int exitCode, String output, boolean timedOut) {

		public static final int OUTPUT_LIMIT = 4_000;

		public boolean success() {
			return !timedOut && exitCode == 0;
		}

		/** 실패 사유 한 줄(로그 · failure_reason 용). */
		public String describe() {
			if (timedOut) {
				return "TIMEOUT";
			}
			String tail = output == null ? "" : output.strip();
			return "exit=" + exitCode + (tail.isEmpty() ? "" : " " + tail);
		}
	}
}
