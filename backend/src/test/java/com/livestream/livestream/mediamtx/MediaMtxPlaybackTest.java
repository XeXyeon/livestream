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

/** downloadRecording: 재생 서버 /get 요청 모양 · 본문을 파일로 · 404 · 빈 응답 · 오류 (DESIGN §4.18 LIVE). */
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

	@Test
	void 그_밖의_오류는_예외() {
		server.expect(requestTo(URL)).andRespond(withServerError());
		assertThatThrownBy(() -> client.downloadRecording("live/3", START, Duration.ofSeconds(30), dir.resolve("c.mp4")))
				.isInstanceOf(RestClientException.class);
	}
}
