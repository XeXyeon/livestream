package com.livestream.livestream.thumbnail;

import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.LiveBroadcastRef;
import com.livestream.livestream.common.config.AppProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 라이브 썸네일 캡처 (DESIGN §4.11): MediaMTX RTSP 에서 한 프레임을 {@code {uploads}/thumbnails/<broadcastId>.jpg} 로 저장한다.
 * <ul>
 *   <li>임시 파일에 쓰고 원자적으로 옮겨, Caddy 가 쓰다 만 jpg 를 서빙하지 않게 한다.</li>
 *   <li>DB 의 {@code thumbnail_path} 는 처음 성공했을 때 한 번만 채운다(이후는 파일만 덮어씀).</li>
 *   <li>캡처하는 동안 방송이 끝났거나 삭제됐으면 결과를 버려 삭제된 방송의 파일이 남지 않게 한다.</li>
 *   <li>한 방송의 실패(타임아웃 10초 등)는 로그만 남기고 다음 방송으로 넘어간다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final BroadcastRepository broadcastRepository;
	private final FfmpegRunner ffmpegRunner;
	private final AppProperties props;

	/** LIVE 방송(삭제 제외)을 모두 캡처한다. 방송 수 만큼 순차로 돈다(회차는 방송당 최대 10초). */
	public void captureAllLive() {
		List<LiveBroadcastRef> targets = broadcastRepository.findLiveRefs();
		for (LiveBroadcastRef t : targets) {
			try {
				capture(t.broadcastId(), t.channelId());
			} catch (RuntimeException e) {
				log.warn("썸네일 캡처 실패: broadcast={} ({})", t.broadcastId(), e.toString());
			}
		}
	}

	/** @return 저장했으면 true */
	boolean capture(Long broadcastId, Long channelId) {
		Path dir = Path.of(props.uploads().dir()).toAbsolutePath().normalize().resolve("thumbnails");
		Path target = dir.resolve(broadcastId + ".jpg");
		Path temp = dir.resolve(broadcastId + ".tmp.jpg");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			log.warn("썸네일 디렉터리 생성 실패: {} ({})", dir, e.toString());
			return false;
		}
		List<String> args = List.of("-rtsp_transport", "tcp", "-i", rtspBase() + "/live/" + channelId,
				"-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "4", "-y", temp.toString());
		FfmpegRunner.Result result = ffmpegRunner.ffmpeg(args, TIMEOUT);
		try {
			if (!result.success() || !Files.isRegularFile(temp) || Files.size(temp) == 0) {
				log.info("썸네일 캡처 실패: broadcast={} {}", broadcastId, result.describe());
				return false;
			}
			if (!broadcastRepository.isLive(broadcastId)) {
				return false; // 캡처 중에 종료 · 삭제됨 — 파일을 남기지 않는다
			}
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			broadcastRepository.assignThumbnailPathIfAbsent(broadcastId, "thumbnails/" + broadcastId + ".jpg");
			return true;
		} catch (IOException e) {
			log.warn("썸네일 저장 실패: broadcast={} ({})", broadcastId, e.toString());
			return false;
		} finally {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
				// 무시
			}
		}
	}

	private String rtspBase() {
		String url = props.mediamtx().rtspUrl();
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}
}
