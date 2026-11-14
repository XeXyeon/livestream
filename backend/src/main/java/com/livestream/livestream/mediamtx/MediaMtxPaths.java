package com.livestream.livestream.mediamtx;

import com.livestream.livestream.broadcast.BroadcastSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MediaMTX 경로 · 쿼리 해석 (DESIGN §2.3: 경로는 {@code live/<channelId>} 하나). */
final class MediaMtxPaths {

	private static final Pattern LIVE_PATH = Pattern.compile("^live/(\\d{1,18})$");
	/**
	 * 송출 쿼리에 허용하는 문자. runOn* 훅은 {@code $MTX_QUERY} 를 셸 명령의 작은따옴표 · JSON 문자열 안에 넣으므로
	 * 따옴표 · 역슬래시 · $ · ` 등이 들어오면 훅 JSON 을 바꾸거나(다른 채널 경로 주입) 셸 명령을 깨뜨릴 수 있다.
	 * 인증 콜백에서 이 밖의 문자가 있으면 publish 를 거절한다.
	 */
	private static final Pattern SAFE_QUERY = Pattern.compile("^[A-Za-z0-9._~=&%+-]*$");

	private MediaMtxPaths() {
	}

	/** {@code live/<channelId>} 이면 channelId, 아니면 null. */
	static Long channelIdOf(String path) {
		if (path == null) {
			return null;
		}
		Matcher m = LIVE_PATH.matcher(path);
		return m.matches() ? Long.valueOf(m.group(1)) : null;
	}

	static String pathOf(Long channelId) {
		return "live/" + channelId;
	}

	static boolean isSafeQuery(String query) {
		return query == null || SAFE_QUERY.matcher(query).matches();
	}

	/** 쿼리 문자열({@code a=1&b=2})에서 첫 번째 name 값을 디코드해 돌려준다. 없으면 null. */
	static String queryParam(String query, String name) {
		if (query == null || query.isEmpty()) {
			return null;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			String key = eq < 0 ? pair : pair.substring(0, eq);
			if (key.equals(name)) {
				String value = eq < 0 ? "" : pair.substring(eq + 1);
				try {
					return URLDecoder.decode(value, StandardCharsets.UTF_8);
				} catch (IllegalArgumentException e) {
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * DESIGN §4.6 source 판정: WebRTC(WHIP)는 쿼리 {@code src}(mobile → BROWSER_MOBILE, obs → STREAM_KEY, 그 외 → BROWSER_PC),
	 * 그 밖의 프로토콜(RTMP 등)은 STREAM_KEY.
	 */
	static BroadcastSource sourceOf(String sourceType, String query) {
		if (!"webRTCSession".equals(sourceType)) {
			return BroadcastSource.STREAM_KEY;
		}
		String src = queryParam(query, "src");
		if ("mobile".equals(src)) {
			return BroadcastSource.BROWSER_MOBILE;
		}
		if ("obs".equals(src)) {
			return BroadcastSource.STREAM_KEY;
		}
		return BroadcastSource.BROWSER_PC;
	}
}
