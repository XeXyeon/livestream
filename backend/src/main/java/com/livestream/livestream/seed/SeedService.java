package com.livestream.livestream.seed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.channel.Category;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.comment.Comment;
import com.livestream.livestream.comment.CommentRepository;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.member.Member;
import com.livestream.livestream.member.MemberRepository;
import com.livestream.livestream.post.Post;
import com.livestream.livestream.post.PostRepository;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingFiles;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.thumbnail.FfmpegRunner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 시연 시드 (DESIGN §5, OPS-4). 없을 때만 만든다: 방송인 · 시청자 계정, 방송인 채널 정보, 어제 끝난 방송 1 +
 * 공개 다시보기(READY), 게시글 "첫 공지", 다시보기 댓글 1.
 * <p>
 * 방송인 계정이 이미 있으면 DB 는 건드리지 않는다(두 번 기동해도 중복 없음). 영상 파일은 매번 없을 때만 복사한다
 * (녹화 볼륨만 새로 만든 경우 대비).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeedService {

	public static final String STREAMER_EMAIL = "demo-streamer@example.com";
	public static final String VIEWER_EMAIL = "demo-viewer@example.com";
	static final String STREAMER_NICKNAME = "데모방송인";
	static final String VIEWER_NICKNAME = "데모시청자";
	/** 녹화 디렉터리 기준 상대 경로. 공개 영상 폴더(vod/)에 둔다. */
	static final String SAMPLE_VIDEO_PATH = "vod/seed-sample.mp4";

	private static final Duration FFMPEG_TIMEOUT = Duration.ofSeconds(30);

	private final AppProperties props;
	private final MemberRepository memberRepository;
	private final ChannelRepository channelRepository;
	private final BroadcastRepository broadcastRepository;
	private final RecordingRepository recordingRepository;
	private final PostRepository postRepository;
	private final CommentRepository commentRepository;
	private final RecordingFiles recordingFiles;
	private final FfmpegRunner ffmpeg;
	private final PasswordEncoder passwordEncoder;
	private final TransactionTemplate tx;

	/** @return 이번에 DB 데이터를 만들었으면 true, 이미 있어서 건너뛰었으면 false */
	public boolean seed() {
		String password = props.seed().password();
		if (password == null || password.isBlank()) {
			throw new IllegalStateException("app.seed.enabled=true 이면 APP_SEED_PASSWORD 가 필요합니다");
		}
		Path video = copySampleVideo();

		if (memberRepository.existsByEmail(STREAMER_EMAIL)) {
			log.info("시드: 이미 있어 건너뜀 ({})", STREAMER_EMAIL);
			return false;
		}
		Integer duration = probeDurationSeconds(video);
		long size = sizeOf(video);
		Long broadcastId = tx.execute(s -> insert(password, duration, size));
		captureThumbnail(video, broadcastId);
		log.info("시드: 계정 2 · 다시보기 1 · 게시글 1 · 댓글 1 생성 (broadcast={})", broadcastId);
		return true;
	}

	private Long insert(String password, Integer duration, long size) {
		String hash = passwordEncoder.encode(password);

		Member streamer = memberRepository.saveAndFlush(Member.create(STREAMER_EMAIL, hash, STREAMER_NICKNAME));
		Channel channel = channelRepository.save(Channel.createFor(streamer));
		channel.updateInfo("데모 방송인", "그리드라이브 시연용 채널입니다.", "데모 방송", Category.GAME, true);

		Member viewer = memberRepository.findByEmail(VIEWER_EMAIL).orElseGet(() -> {
			Member m = memberRepository.saveAndFlush(Member.create(VIEWER_EMAIL, hash, VIEWER_NICKNAME));
			channelRepository.save(Channel.createFor(m));
			return m;
		});

		Instant endedAt = Instant.now().minus(Duration.ofDays(1));
		Instant startedAt = endedAt.minusSeconds(duration == null ? 10 : duration);
		Broadcast broadcast = broadcastRepository.save(
				Broadcast.endedForSeed(channel, BroadcastSource.BROWSER_PC, startedAt, endedAt));

		Recording recording = Recording.startFor(broadcast, true);
		recording.markProcessing();
		recording.markReady(SAMPLE_VIDEO_PATH, duration, size);
		recordingRepository.save(recording);

		postRepository.save(Post.write(channel, "첫 공지",
				"그리드라이브 데모 채널입니다. 다시보기와 커뮤니티 댓글을 자유롭게 남겨 주세요."));
		commentRepository.save(Comment.onRecording(viewer, recording, null, "다시보기 잘 봤습니다!"));
		return broadcast.getId();
	}

	/** 샘플 영상을 녹화 디렉터리 vod/ 로 복사한다(이미 있으면 그대로). */
	private Path copySampleVideo() {
		Path source = Path.of(props.seed().samplePath()).toAbsolutePath().normalize();
		Path target = recordingFiles.resolve(SAMPLE_VIDEO_PATH);
		if (target == null) {
			throw new IllegalStateException("시드 영상 대상 경로를 만들 수 없습니다: " + SAMPLE_VIDEO_PATH);
		}
		if (Files.isRegularFile(target)) {
			return target;
		}
		if (!Files.isRegularFile(source)) {
			throw new IllegalStateException("시드 샘플 영상이 없습니다: " + source + " (app.seed.sample-path)");
		}
		try {
			Files.createDirectories(target.getParent());
			Files.copy(source, target);
			return target;
		} catch (IOException e) {
			throw new IllegalStateException("시드 샘플 영상 복사 실패: " + source + " → " + target, e);
		}
	}

	/** ffprobe 로 길이(초, 반올림). 실패하면 null — 다시보기는 길이 없이도 재생된다. */
	private Integer probeDurationSeconds(Path file) {
		FfmpegRunner.Result r = ffmpeg.ffprobe(List.of("-v", "error", "-show_entries", "format=duration",
				"-of", "default=noprint_wrappers=1:nokey=1", file.toString()), FFMPEG_TIMEOUT);
		if (!r.success()) {
			log.warn("시드: ffprobe 실패 {} ({})", file, r.describe());
			return null;
		}
		try {
			return (int) Math.round(Double.parseDouble(r.output().strip()));
		} catch (NumberFormatException e) {
			log.warn("시드: ffprobe 출력 해석 실패: {}", r.output());
			return null;
		}
	}

	private static long sizeOf(Path file) {
		try {
			return Files.size(file);
		} catch (IOException e) {
			throw new IllegalStateException("시드 영상 크기를 읽지 못했습니다: " + file, e);
		}
	}

	/** 다시보기 카드용 썸네일: 영상 2초 지점 한 프레임을 {@code thumbnails/<broadcastId>.jpg} 로. 실패는 로그만. */
	private void captureThumbnail(Path video, Long broadcastId) {
		Path dir = Path.of(props.uploads().dir()).toAbsolutePath().normalize().resolve("thumbnails");
		Path target = dir.resolve(broadcastId + ".jpg");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			log.warn("시드: 썸네일 디렉터리 생성 실패 {} ({})", dir, e.toString());
			return;
		}
		FfmpegRunner.Result r = ffmpeg.ffmpeg(List.of("-ss", "2", "-i", video.toString(),
				"-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "4", "-y", target.toString()), FFMPEG_TIMEOUT);
		if (!r.success() || !Files.isRegularFile(target)) {
			log.warn("시드: 썸네일 생성 실패 ({})", r.describe());
			return;
		}
		broadcastRepository.assignThumbnailPathIfAbsent(broadcastId, "thumbnails/" + broadcastId + ".jpg");
	}
}
