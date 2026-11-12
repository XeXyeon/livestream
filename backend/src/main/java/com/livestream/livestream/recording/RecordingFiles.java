package com.livestream.livestream.recording;

import com.livestream.livestream.common.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 녹화 디렉터리(app.recording.dir, MediaMTX 와 공유하는 볼륨) 안의 경로 다루기.
 * DB 에는 이 디렉터리 기준 상대 경로만 둔다({@code raw/…}, {@code vod/<uuid>.mp4}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingFiles {

	/** MediaMTX recordPath {@code /recordings/raw/%path/…} → {@code raw/live/<channelId>/<파일 이름>}. 파일 이름은 안전한 문자만. */
	private static final Pattern RAW_SEGMENT = Pattern.compile("raw/live/(\\d{1,18})/[A-Za-z0-9_][A-Za-z0-9_.-]{0,200}");

	private final AppProperties props;

	/**
	 * MediaMTX 가 보낸 조각 경로(컨테이너 안 절대 경로, 예: {@code /recordings/raw/live/12/2026-…-123456.mp4})를
	 * 녹화 디렉터리 기준 상대 경로로 바꾼다. 마운트 위치가 backend 와 달라도 되도록 마지막 {@code raw/} 부터 자른다.
	 *
	 * @return 형식이 맞고 그 채널의 조각이면 상대 경로, 아니면 null
	 */
	public static String toRelativeRawSegment(String segmentPath, Long channelId) {
		if (segmentPath == null) {
			return null;
		}
		String normalized = segmentPath.replace('\\', '/');
		String relative;
		if (normalized.startsWith("raw/")) {
			relative = normalized;
		} else {
			int at = normalized.lastIndexOf("/raw/live/");
			if (at < 0) {
				return null;
			}
			relative = normalized.substring(at + 1);
		}
		Matcher m = RAW_SEGMENT.matcher(relative);
		if (!m.matches() || relative.contains("..")) {
			return null;
		}
		return Long.parseLong(m.group(1)) == channelId ? relative : null;
	}

	/** 상대 경로 → 절대 경로. 녹화 디렉터리 밖을 가리키면 null. */
	public Path resolve(String relative) {
		if (relative == null || relative.isBlank()) {
			return null;
		}
		Path base = baseDir();
		Path target = base.resolve(relative).normalize();
		if (!target.startsWith(base) || target.equals(base)) {
			log.warn("녹화 디렉터리 밖 경로: {}", relative);
			return null;
		}
		return target;
	}

	public Path baseDir() {
		return Path.of(props.recording().dir()).toAbsolutePath().normalize();
	}

	/** 지우고 실패는 로그만 남긴다. */
	public void deleteQuietly(String relative) {
		Path target = resolve(relative);
		if (target == null) {
			return;
		}
		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			log.warn("녹화 파일 삭제 실패: {} ({})", target, e.toString());
		}
	}
}
