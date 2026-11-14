package com.livestream.livestream.mediamtx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.livestream.livestream.common.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * MediaMTX Control API 클라이언트 (DESIGN §4.6). 내부망 전용(:9997, authHTTPExclude 로 인증 콜백을 거치지 않음).
 * 타임아웃 3초.
 */
@Slf4j
@Component
public class MediaMtxClient {

	private static final Duration TIMEOUT = Duration.ofSeconds(3);

	private final RestClient restClient;

	@Autowired
	public MediaMtxClient(AppProperties props) {
		this(RestClient.builder().baseUrl(props.mediamtx().apiUrl()).requestFactory(requestFactory()));
	}

	MediaMtxClient(RestClient.Builder builder) {
		this.restClient = builder.build();
	}

	/**
	 * 경로의 현재 송출자를 끊는다: {@code GET /v3/paths/get/{path}} → source.type · id → 종류별 kick.
	 *
	 * @param path {@code live/<channelId>} (호출 쪽이 형식을 보장한다)
	 * @return 끊었으면 true, 송출자가 없으면(경로 없음 · source 없음) false
	 * @throws org.springframework.web.client.RestClientException MediaMTX 호출 실패(호출 쪽이 로그만 남긴다)
	 */
	public boolean kickPublisher(String path) {
		PathInfo info = getPath(path);
		if (info == null || info.source() == null || info.source().id() == null) {
			return false;
		}
		String kickBase = switch (info.source().type()) {
			case "webRTCSession" -> "/v3/webrtcsessions/kick/";
			case "rtmpConn" -> "/v3/rtmpconns/kick/";
			case "rtmpsConn" -> "/v3/rtmpsconns/kick/";
			case "rtspSession" -> "/v3/rtspsessions/kick/";
			case "rtspsSession" -> "/v3/rtspssessions/kick/";
			case "srtConn" -> "/v3/srtconns/kick/";
			case null, default -> null;
		};
		if (kickBase == null) {
			log.warn("kick 할 수 없는 송출 종류: path={} type={}", path, info.source().type());
			return false;
		}
		restClient.post().uri(kickBase + "{id}", info.source().id()).retrieve().toBodilessEntity();
		return true;
	}

	/**
	 * 경로에 지금 송출자가 붙어 있는지. 늦게 도착한 not-ready 훅(새 연결의 ready 보다 늦게 온 옛 연결의 훅)을 걸러 내는 데 쓴다.
	 *
	 * @throws org.springframework.web.client.RestClientException MediaMTX 호출 실패
	 */
	public boolean hasPublisher(String path) {
		PathInfo info = getPath(path);
		return info != null && info.source() != null;
	}

	/** 경로 정보. 경로가 없으면(송출자 없음) null. */
	private PathInfo getPath(String path) {
		try {
			return restClient.get().uri("/v3/paths/get/" + path).retrieve().body(PathInfo.class);
		} catch (HttpClientErrorException.NotFound e) {
			return null;
		}
	}

	private static JdkClientHttpRequestFactory requestFactory() {
		HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(TIMEOUT);
		return factory;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PathInfo(Source source) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Source(String type, String id) {
		}
	}
}
