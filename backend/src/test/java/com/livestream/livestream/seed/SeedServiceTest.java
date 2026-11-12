package com.livestream.livestream.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastStatus;
import com.livestream.livestream.channel.Category;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingStatus;
import com.livestream.livestream.recording.Visibility;
import com.livestream.livestream.thumbnail.FfmpegRunner;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OPS-4 (DESIGN §5 · §9 OPS-4): app.seed.enabled=true 로 기동하면 계정 · 다시보기 · 게시글 · 댓글이 생기고,
 * 다시 실행해도(= 두 번째 기동) 중복이 생기지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeedServiceTest {

	private static final String PASSWORD = "seed-test-password";
	private static final byte[] SAMPLE = "fake-mp4-bytes".getBytes();

	@TempDir static Path tempDir;

	@DynamicPropertySource
	static void seedProps(DynamicPropertyRegistry registry) {
		registry.add("app.seed.enabled", () -> "true");
		registry.add("app.seed.password", () -> PASSWORD);
		registry.add("app.seed.sample-path", SeedServiceTest::writeSample);
		registry.add("app.recording.dir", () -> tempDir.resolve("recordings").toString());
		registry.add("app.uploads.dir", () -> tempDir.resolve("uploads").toString());
	}

	private static String writeSample() {
		try {
			Path sample = tempDir.resolve("sample.mp4");
			if (!Files.exists(sample)) {
				Files.write(sample, SAMPLE);
			}
			return sample.toString();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 기동 중에 SeedRunner 가 부르므로 목이 아니라 미리 동작이 정해진 가짜를 쓴다. */
	@TestConfiguration
	static class FakeFfmpeg {
		@Bean
		@Primary
		FfmpegRunner fakeFfmpegRunner() {
			return new FfmpegRunner() {
				@Override
				public Result ffmpeg(List<String> args, Duration timeout) {
					try {
						Files.write(Path.of(args.getLast()), new byte[] {1});
					} catch (IOException e) {
						return new Result(1, e.toString(), false);
					}
					return new Result(0, "", false);
				}

				@Override
				public Result ffprobe(List<String> args, Duration timeout) {
					return new Result(0, "10.2\n", false);
				}
			};
		}
	}

	@Autowired MockMvc mvc;
	@Autowired SeedService seedService;
	@Autowired EntityManager em;
	@Autowired TransactionTemplate tx;
	@MockitoBean MediaMtxClient mediaMtxClient;

	@Test
	void 기동하면_시드가_들어가고_다시_실행해도_중복이_없다() throws Exception {
		assertSeeded();

		assertThat(seedService.seed()).isFalse();

		assertSeeded();
	}

	@Test
	void 시드_계정으로_로그인되고_공개_다시보기_목록에_보인다() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + SeedService.VIEWER_EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk());

		String body = mvc.perform(get("/api/recordings").param("size", "50"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> titles = JsonPath.read(body, "$.items[*].title");
		assertThat(titles).contains("데모 방송");
	}

	@Test
	void 영상_파일이_지워졌으면_다시_실행할_때_다시_복사한다() throws Exception {
		Path video = tempDir.resolve("recordings").resolve(SeedService.SAMPLE_VIDEO_PATH);
		Files.delete(video);

		seedService.seed();

		assertThat(video).hasBinaryContent(SAMPLE);
	}

	private void assertSeeded() {
		tx.executeWithoutResult(s -> {
			assertThat(countMembers(SeedService.STREAMER_EMAIL)).isEqualTo(1);
			assertThat(countMembers(SeedService.VIEWER_EMAIL)).isEqualTo(1);

			Channel channel = em.createQuery("select c from Channel c where c.member.email = :e", Channel.class)
					.setParameter("e", SeedService.STREAMER_EMAIL).getSingleResult();
			assertThat(channel.getName()).isEqualTo("데모 방송인");
			assertThat(channel.getTitle()).isEqualTo("데모 방송");
			assertThat(channel.getCategory()).isEqualTo(Category.GAME);

			List<Broadcast> broadcasts = em.createQuery("select b from Broadcast b where b.channel = :c", Broadcast.class)
					.setParameter("c", channel).getResultList();
			assertThat(broadcasts).hasSize(1);
			Broadcast broadcast = broadcasts.getFirst();
			assertThat(broadcast.getStatus()).isEqualTo(BroadcastStatus.ENDED);
			assertThat(broadcast.getEndedAt()).isNotNull();
			assertThat(broadcast.getThumbnailPath()).isEqualTo("thumbnails/" + broadcast.getId() + ".jpg");

			Recording recording = em.createQuery("select r from Recording r where r.broadcast = :b", Recording.class)
					.setParameter("b", broadcast).getSingleResult();
			assertThat(recording.getStatus()).isEqualTo(RecordingStatus.READY);
			assertThat(recording.getVisibility()).isEqualTo(Visibility.PUBLIC);
			assertThat(recording.getFilePath()).isEqualTo(SeedService.SAMPLE_VIDEO_PATH);
			assertThat(recording.getDurationSeconds()).isEqualTo(10);
			assertThat(recording.getSizeBytes()).isEqualTo(SAMPLE.length);

			assertThat(count("select count(p) from Post p where p.channel = :x", channel)).isEqualTo(1);
			assertThat(count("select count(c) from Comment c where c.recording = :x", recording)).isEqualTo(1);
		});
		assertThat(tempDir.resolve("recordings").resolve(SeedService.SAMPLE_VIDEO_PATH)).hasBinaryContent(SAMPLE);
	}

	private long countMembers(String email) {
		return em.createQuery("select count(m) from Member m where m.email = :e", Long.class)
				.setParameter("e", email).getSingleResult();
	}

	private long count(String jpql, Object param) {
		return em.createQuery(jpql, Long.class).setParameter("x", param).getSingleResult();
	}
}
