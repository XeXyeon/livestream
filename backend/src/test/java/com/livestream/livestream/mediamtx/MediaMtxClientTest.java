package com.livestream.livestream.mediamtx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** kickPublisher: 경로 조회 → source 종류별 kick 엔드포인트 (DESIGN §4.6). */
class MediaMtxClientTest {

	private MockRestServiceServer server;
	private MediaMtxClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://mediamtx:9997");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new MediaMtxClient(builder);
	}

	@Test
	void WebRTC_송출자는_webrtcsessions로_kick한다() {
		pathReturns("{\"name\":\"live/3\",\"ready\":true,\"source\":{\"type\":\"webRTCSession\",\"id\":\"abc-123\"},\"readers\":[]}");
		server.expect(requestTo("http://mediamtx:9997/v3/webrtcsessions/kick/abc-123")).andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess());

		assertThat(client.kickPublisher("live/3")).isTrue();
		server.verify();
	}

	@Test
	void RTMP_송출자는_rtmpconns로_kick한다() {
		pathReturns("{\"source\":{\"type\":\"rtmpConn\",\"id\":\"r-1\"}}");
		server.expect(requestTo("http://mediamtx:9997/v3/rtmpconns/kick/r-1")).andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess());

		assertThat(client.kickPublisher("live/3")).isTrue();
		server.verify();
	}

	@Test
	void 경로가_없거나_송출자가_없으면_false() {
		server.expect(requestTo("http://mediamtx:9997/v3/paths/get/live/3")).andRespond(withStatus(HttpStatus.NOT_FOUND));
		assertThat(client.kickPublisher("live/3")).isFalse();
		server.verify();
		server.reset();

		pathReturns("{\"source\":null}");
		assertThat(client.kickPublisher("live/3")).isFalse();
		server.verify();
	}

	@Test
	void 송출자_유무를_경로_source로_판단한다() {
		pathReturns("{\"ready\":true,\"source\":{\"type\":\"rtmpConn\",\"id\":\"r-1\"}}");
		assertThat(client.hasPublisher("live/3")).isTrue();
		server.verify();
		server.reset();

		pathReturns("{\"ready\":false,\"source\":null}");
		assertThat(client.hasPublisher("live/3")).isFalse();
		server.verify();
		server.reset();

		server.expect(requestTo("http://mediamtx:9997/v3/paths/get/live/3")).andRespond(withStatus(HttpStatus.NOT_FOUND));
		assertThat(client.hasPublisher("live/3")).isFalse();
		server.verify();
	}

	@Test
	void MediaMTX_오류는_예외로_올린다() {
		server.expect(requestTo("http://mediamtx:9997/v3/paths/get/live/3")).andRespond(withServerError());
		assertThatThrownBy(() -> client.kickPublisher("live/3")).isInstanceOf(RestClientException.class);
	}

	private void pathReturns(String json) {
		server.expect(requestTo("http://mediamtx:9997/v3/paths/get/live/3")).andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
	}

	@Test
	void 경로_목록에서_ready인_경로만_모두_읽는다() {
		server.expect(requestTo("http://mediamtx:9997/v3/paths/list?itemsPerPage=1000&page=0")).andRespond(withSuccess(
				"{\"pageCount\":2,\"itemCount\":3,\"items\":[{\"name\":\"live/1\",\"ready\":true},{\"name\":\"live/2\",\"ready\":false}]}",
				MediaType.APPLICATION_JSON));
		server.expect(requestTo("http://mediamtx:9997/v3/paths/list?itemsPerPage=1000&page=1")).andRespond(withSuccess(
				"{\"pageCount\":2,\"itemCount\":3,\"items\":[{\"name\":\"live/3\",\"ready\":true}]}",
				MediaType.APPLICATION_JSON));

		assertThat(client.listReadyPaths()).containsExactlyInAnyOrder("live/1", "live/3");
		server.verify();
	}

	@Test
	void 경로_목록_조회가_실패하면_예외를_던진다() {
		server.expect(requestTo("http://mediamtx:9997/v3/paths/list?itemsPerPage=1000&page=0")).andRespond(withServerError());
		assertThatThrownBy(() -> client.listReadyPaths()).isInstanceOf(RestClientException.class);
	}
}
