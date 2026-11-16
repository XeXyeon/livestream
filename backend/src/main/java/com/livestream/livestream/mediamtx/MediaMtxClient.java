package com.livestream.livestream.mediamtx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.livestream.livestream.common.config.AppProperties;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * MediaMTX Control API 클라이언트 (DESIGN §4.6). 내부망 전용(:9997, authHTTPExclude 로 인증 콜백을 거치지 않음).
 * 타임아웃 3초. 라이브 클립(§4.18)은 재생 서버(:9996, {@code app.mediamtx.playback-url}, 내부망 전용)에서 받는다.
 */
@Slf4j
@Component
public class MediaMtxClient {

	private static final Duration TIMEOUT = Duration.ofSeconds(3);

	private final RestClient restClient;
	private final RestClient playbackClient;

	@Autowired
	public MediaMtxClient(AppProperties props) {
		this(RestClient.builder().baseUrl(props.mediamtx().apiUrl()).requestFactory(requestFactory(TIMEOUT)),
				playbackBuilder(props));
	}

	MediaMtxClient(RestClient.Builder builder) {
		this(builder, RestClient.builder());
	}

	MediaMtxClient(RestClient.Builder apiBuilder, RestClient.Builder playbackBuilder) {
		this.restClient = apiBuilder.build();
		this.playbackClient = playbackBuilder.build();
	}

	private static RestClient.Builder playbackBuilder(AppProperties props) {
		RestClient.Builder b = RestClient.builder().requestFactory(requestFactory(props.clip().timeout()));
		String url = props.mediamtx().playbackUrl();
		return url == null || url.isBlank() ? b : b.baseUrl(url);
	}

	/**
	 * 녹화된 구간을 mp4 로 받아 {@code target} 에 쓴다(라이브 클립, DESIGN §4.18):
	 * {@code GET {playback-url}/get?path=&start=<RFC3339>&duration=<N>s&format=mp4}. 녹화 중인 조각도 내준다(MediaMTX v1.21.3).
	 * 응답 본문을 메모리에 모으지 않고 파일로 흘려 쓴다. 읽기 타임아웃은 {@code app.clip.timeout}.
	 *
	 * @param path     {@code live/<channelId>}
	 * @param start    구간 시작 시각
	 * @param duration 구간 길이(초 단위로 보낸다)
	 * @return 받은 내용이 있으면 true. 404(그 구간에 녹화 없음) · 빈 응답이면 false(파일은 지운다)
	 * @throws org.springframework.web.client.RestClientException 연결 실패 · 타임아웃 · 그 밖의 HTTP 오류 · 파일 쓰기 실패
	 *         (본문 처리 중 IOException 은 RestClient 가 ResourceAccessException 으로 감싼다)
	 */
	public boolean downloadRecording(String path, Instant start, Duration duration, Path target) {
		String startText = DateTimeFormatter.ISO_INSTANT.format(start.truncatedTo(ChronoUnit.MILLIS));
		Boolean written = playbackClient.get()
				.uri(b -> b.path("/get").queryParam("path", path).queryParam("start", startText)
						.queryParam("duration", duration.toSeconds() + "s").queryParam("format", "mp4").build())
				.exchange((request, response) -> {
					int status = response.getStatusCode().value();
					if (status == 404) {
						return false;
					}
					if (!response.getStatusCode().is2xxSuccessful()) {
						throw new RestClientException("재생 서버 응답 " + status);
					}
					try (InputStream in = response.getBody()) {
						Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
					}
					if (Files.size(target) == 0) {
						Files.deleteIfExists(target);
						return false;
					}
					return true;
				});
		return Boolean.TRUE.equals(written);
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

	private static JdkClientHttpRequestFactory requestFactory(Duration readTimeout) {
		HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(readTimeout);
		return factory;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PathInfo(Source source) {

		@JsonIgnoreProperties(ignoreUnknown = true)
		record Source(String type, String id) {
		}
	}
}
