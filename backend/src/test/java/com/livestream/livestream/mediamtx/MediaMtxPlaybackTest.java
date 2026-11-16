package com.livestream.livestream.mediamtx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** downloadRecording · listSegments: 재생 서버 /get · /list 요청 모양 · 응답 처리 · 404 · 오류 (DESIGN §4.18 LIVE · ①). */
class MediaMtxPlaybackTest {

	private static final String URL = "http://mediamtx:9996/get?path=live/3&start=2026-11-16T01:00:00.500Z&duration=30s&format=mp4";
	private static final Instant START = Instant.parse("2026-11-16T01:00:00.500123Z");

	@TempDir Path dir;
	private MockRestServiceServer server;
	private MediaMtxClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder playback = RestClient.builder().baseUrl("http://mediamtx:9996");
		server = MockRestServiceServer.bindTo(playback).build();
		client = new MediaMtxClient(RestClient.builder(), playback);
	}

	@Test
	void 구간을_RFC3339와_초_단위로_요청하고_본문을_파일에_쓴다() throws Exception {
		server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.parseMediaType("video/mp4")));
		Path target = dir.resolve("x.mp4");

		assertThat(client.downloadRecording("live/3", START, Duration.ofSeconds(30), target)).isTrue();
		assertThat(Files.readAllBytes(target)).containsExactly(1, 2, 3);
		server.verify();
	}

	@Test
	void 녹화가_없는_구간_404와_빈_응답은_false() {
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		assertThat(client.downloadRecording("live/3", START, Duration.ofSeconds(30), dir.resolve("a.mp4"))).isFalse();

		server.reset();
		server.expect(requestTo(URL)).andRespond(withSuccess(new byte[0], MediaType.parseMediaType("video/mp4")));
		Path empty = dir.resolve("b.mp4");
		assertThat(client.downloadRecording("live/3", START, Duration.ofSeconds(30), empty)).isFalse();
		assertThat(empty).doesNotExist();
	}

	private static final String LIST_URL =
			"http://mediamtx:9996/list?path=live/3&start=2026-11-16T01:00:00.500Z&end=2026-11-16T01:02:00.500Z";

	@Test
	void 조각_목록을_시작순으로_읽고_오프셋_시각도_읽는다() {
		server.expect(requestTo(LIST_URL)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess("""
				[{"start":"2026-11-16T10:00:04.81+09:00","duration":106.2,"url":"x"},
				 {"start":"2026-11-16T01:00:02.762Z","duration":5},
				 {"start":"잘못된 시각","duration":3},
				 {"start":"2026-11-16T01:00:09Z"}]
				""", MediaType.APPLICATION_JSON));

		var segments = client.listSegments("live/3", START, START.plusSeconds(120));
		assertThat(segments).extracting(MediaMtxClient.RecordingSegment::start)
				.containsExactly(Instant.parse("2026-11-16T01:00:02.762Z"), Instant.parse("2026-11-16T01:00:04.810Z"));
		assertThat(segments.get(1).durationSeconds()).isEqualTo(106.2);
		assertThat(segments.get(0).end()).isEqualTo(Instant.parse("2026-11-16T01:00:07.762Z"));
		server.verify();
	}

	@Test
	void 조각_목록_404는_빈_목록_그_밖의_오류는_예외() {
		server.expect(requestTo(LIST_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
		assertThat(client.listSegments("live/3", START, START.plusSeconds(120))).isEmpty();

		server.reset();
		server.expect(requestTo(LIST_URL)).andRespond(withServerError());
		assertThatThrownBy(() -> client.listSegments("live/3", START, START.plusSeconds(120)))
				.isInstanceOf(RestClientException.class);
	}

	@Test
	void 그_밖의_오류는_예외() {
		server.expect(requestTo(URL)).andRespond(withServerError());
		assertThatThrownBy(() -> client.downloadRecording("live/3", START, Duration.ofSeconds(30), dir.resolve("c.mp4")))
				.isInstanceOf(RestClientException.class);
	}
}
