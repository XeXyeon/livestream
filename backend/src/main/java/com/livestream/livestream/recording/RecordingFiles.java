package com.livestream.livestream.recording;

import com.livestream.livestream.common.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 녹화 디렉터리(app.recording.dir, MediaMTX 와 공유하는 볼륨) 안의 경로 다루기.
 * DB 에는 이 디렉터리 기준 상대 경로만 둔다({@code raw/…}, {@code vod/<uuid>.mp4}, {@code private/<uuid>.mp4}).
 * <p>
 * 다시보기 영상은 공개면 {@code vod/}(Caddy 가 정적으로 내준다), 비공개면 {@code private/}(Spring 만 읽는다)에 둔다(DESIGN §4.10).
 * 두 폴더는 같은 볼륨이라 옮기기는 rename 한 번이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingFiles {

	/** MediaMTX recordPath {@code /recordings/raw/%path/…} → {@code raw/live/<channelId>/<파일 이름>}. 파일 이름은 안전한 문자만. */
	private static final Pattern RAW_SEGMENT = Pattern.compile("raw/live/(\\d{1,18})/[A-Za-z0-9_][A-Za-z0-9_.-]{0,200}");

	/** 공개 영상 폴더. Caddy 가 {@code /vod/*} 로 내준다. */
	static final String PUBLIC_DIR = "vod/";
	/** 비공개 영상 폴더. Caddy 가 내주지 않는다. 병합 결과는 항상 여기에 먼저 쓴다. */
	static final String PRIVATE_DIR = "private/";

	private final AppProperties props;

	/** 공개 설정에 맞는 영상 폴더({@code vod/} 또는 {@code private/}). */
	static String folderFor(Visibility visibility) {
		return visibility == Visibility.PUBLIC ? PUBLIC_DIR : PRIVATE_DIR;
	}

	/** 영상 상대 경로를 같은 파일 이름 그대로 공개 설정에 맞는 폴더로 바꾼 경로. 영상 경로가 아니면 null. */
	static String videoPathFor(String relative, Visibility visibility) {
		String name = videoFileName(relative);
		return name == null ? null : folderFor(visibility) + name;
	}

	/** {@code vod/x.mp4} ↔ {@code private/x.mp4}. 영상 경로가 아니면 null. */
	static String counterpart(String relative) {
		String name = videoFileName(relative);
		if (name == null) {
			return null;
		}
		return (relative.startsWith(PUBLIC_DIR) ? PRIVATE_DIR : PUBLIC_DIR) + name;
	}

	/** 영상 폴더 바로 아래 파일 이름. {@code vod/} · {@code private/} 아래가 아니거나 하위 폴더 · 상위 경로면 null. */
	private static String videoFileName(String relative) {
		if (relative == null) {
			return null;
		}
		String name;
		if (relative.startsWith(PUBLIC_DIR)) {
			name = relative.substring(PUBLIC_DIR.length());
		} else if (relative.startsWith(PRIVATE_DIR)) {
			name = relative.substring(PRIVATE_DIR.length());
		} else {
			return null;
		}
		return name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals("..") || name.equals(".") ? null : name;
	}

	/**
	 * 영상 파일이 지금 있는 상대 경로. {@code file_path} 에 없으면 다른 영상 폴더의 같은 파일 이름을 한 번 더 본다
	 * (파일을 옮긴 뒤 DB 커밋만 실패한 경우, DESIGN §4.10). 둘 다 없으면 null.
	 */
	public String locateVideo(String relative) {
		if (isRegularFile(relative)) {
			return relative;
		}
		String other = counterpart(relative);
		return other != null && isRegularFile(other) ? other : null;
	}

	private boolean isRegularFile(String relative) {
		Path p = resolve(relative);
		return p != null && Files.isRegularFile(p);
	}

	/** 영상 파일을 옮긴다(같은 볼륨이라 {@code ATOMIC_MOVE} = rename). 대상 폴더가 없으면 만든다. */
	public void moveVideo(String fromRelative, String toRelative) throws IOException {
		Path from = resolve(fromRelative);
		Path to = resolve(toRelative);
		if (from == null || to == null) {
			throw new IOException("녹화 디렉터리 밖 경로: " + fromRelative + " → " + toRelative);
		}
		Files.createDirectories(to.getParent());
		Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
	}

	/** 영상 파일을 지운다: {@code file_path} 와 다른 영상 폴더의 같은 파일 이름 둘 다. 실패는 로그만. */
	public void deleteVideoQuietly(String relative) {
		deleteQuietly(relative);
		String other = counterpart(relative);
		if (other != null) {
			deleteQuietly(other);
		}
	}

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
