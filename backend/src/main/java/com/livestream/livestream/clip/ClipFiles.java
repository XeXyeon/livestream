package com.livestream.livestream.clip;

import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.recording.RecordingFiles;
import com.livestream.livestream.recording.Visibility;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 클립 파일 경로 (DESIGN §4.18). 다시보기({@link RecordingFiles}, §4.10)와 같은 규칙에 하위 폴더 {@code clips/} 만 다르다:
 * 공개면 {@code vod/clips/}(Caddy 정적), 비공개면 {@code private/clips/}(Spring 만 읽음). 처리 결과는 항상 비공개 폴더에 먼저 쓴다.
 * 썸네일은 업로드 디렉터리의 {@code thumbnails/clips/<clipId>.jpg}. 라이브 클립 초안(CLIP-3)은 항상 {@code private/clip-drafts/}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClipFiles {

	static final String PUBLIC_DIR = "vod/clips/";
	static final String PRIVATE_DIR = "private/clips/";
	static final String THUMBNAIL_DIR = "thumbnails/clips/";
	static final String DRAFT_DIR = "private/clip-drafts/";

	private final RecordingFiles recordingFiles;
	private final AppProperties props;

	static String folderFor(Visibility visibility) {
		return visibility == Visibility.PUBLIC ? PUBLIC_DIR : PRIVATE_DIR;
	}

	/** 같은 파일 이름 그대로 공개 설정에 맞는 폴더로 바꾼 경로. 클립 영상 경로가 아니면 null. */
	static String videoPathFor(String relative, Visibility visibility) {
		String name = videoFileName(relative);
		return name == null ? null : folderFor(visibility) + name;
	}

	/** {@code vod/clips/x.mp4} ↔ {@code private/clips/x.mp4}. 클립 영상 경로가 아니면 null. */
	static String counterpart(String relative) {
		String name = videoFileName(relative);
		if (name == null) {
			return null;
		}
		return (relative.startsWith(PUBLIC_DIR) ? PRIVATE_DIR : PUBLIC_DIR) + name;
	}

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

	static String thumbnailPathFor(Long clipId) {
		return THUMBNAIL_DIR + clipId + ".jpg";
	}

	/** 영상 파일이 지금 있는 상대 경로. {@code file_path} 에 없으면 다른 폴더의 같은 파일 이름을 본다. 둘 다 없으면 null. */
	public String locateVideo(String relative) {
		if (isRegularFile(relative)) {
			return relative;
		}
		String other = counterpart(relative);
		return other != null && isRegularFile(other) ? other : null;
	}

	/** 같은 볼륨 안에서 옮긴다(ATOMIC_MOVE). */
	public void moveVideo(String fromRelative, String toRelative) throws IOException {
		recordingFiles.moveVideo(fromRelative, toRelative);
	}

	/** {@code file_path} 와 다른 폴더의 같은 파일 이름 둘 다 지운다. 실패는 로그만. */
	public void deleteVideoQuietly(String relative) {
		recordingFiles.deleteQuietly(relative);
		String other = counterpart(relative);
		if (other != null) {
			recordingFiles.deleteQuietly(other);
		}
	}

	/** 라이브 클립 초안 파일을 지운다(초안 폴더 밖 경로는 무시). 실패는 로그만. */
	public void deleteDraftQuietly(String relative) {
		if (relative != null && relative.startsWith(DRAFT_DIR)) {
			recordingFiles.deleteQuietly(relative);
		}
	}

	/** 녹화 디렉터리 기준 상대 경로 → 절대 경로. 밖을 가리키면 null. */
	public Path resolve(String relative) {
		return recordingFiles.resolve(relative);
	}

	/** 업로드 디렉터리 기준 상대 경로 → 절대 경로. 밖을 가리키면 null. */
	public Path resolveUpload(String relative) {
		if (relative == null || relative.isBlank()) {
			return null;
		}
		Path base = Path.of(props.uploads().dir()).toAbsolutePath().normalize();
		Path target = base.resolve(relative).normalize();
		if (!target.startsWith(base) || target.equals(base)) {
			log.warn("업로드 디렉터리 밖 경로: {}", relative);
			return null;
		}
		return target;
	}

	public void deleteThumbnailQuietly(String relative) {
		Path p = resolveUpload(relative);
		if (p == null) {
			return;
		}
		try {
			Files.deleteIfExists(p);
		} catch (IOException e) {
			log.warn("클립 썸네일 삭제 실패: {} ({})", p, e.toString());
		}
	}

	private boolean isRegularFile(String relative) {
		Path p = resolve(relative);
		return p != null && Files.isRegularFile(p);
	}
}
