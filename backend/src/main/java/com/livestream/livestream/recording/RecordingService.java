package com.livestream.livestream.recording;

import com.livestream.livestream.common.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 다시보기 파일 관리. BC-2 는 방송 삭제 때의 파일 삭제만 둔다(병합 · 조회는 REC-1 · REC-2). */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingService {

	private final AppProperties props;

	/**
	 * 방송 삭제 뒤(커밋 뒤) 파일을 지운다: vod(recording.file_path, recording.dir 기준) · 썸네일(uploads 기준).
	 * 각 경로는 기준 디렉터리 밖을 가리키면 지우지 않는다. 실패는 로그만 남긴다.
	 * raw 조각은 여기서 지우지 않는다: 병합(RecordingMergeService)이 FILE_DELETED 를 보고 조각 파일 · 행을 지우고,
	 * 늦게 온 조각은 RecordingSegmentService 가 바로 지운다(REC-1).
	 *
	 * @param vodFilePath   {@code vod/<uuid>.mp4}, 없으면 null
	 * @param thumbnailPath {@code thumbnails/<broadcastId>.jpg}, 없으면 null
	 */
	public void deleteFiles(String vodFilePath, String thumbnailPath) {
		deleteUnder(props.recording().dir(), vodFilePath);
		deleteUnder(props.uploads().dir(), thumbnailPath);
	}

	private static void deleteUnder(String baseDir, String relative) {
		if (relative == null || relative.isBlank()) {
			return;
		}
		Path base = Path.of(baseDir).toAbsolutePath().normalize();
		Path target = base.resolve(relative).normalize();
		if (!target.startsWith(base) || target.equals(base)) {
			log.warn("기준 디렉터리 밖 경로라 지우지 않음: {}", relative);
			return;
		}
		try {
			Files.deleteIfExists(target);
		} catch (IOException e) {
			log.warn("파일 삭제 실패: {} ({})", target, e.toString());
		}
	}
}
