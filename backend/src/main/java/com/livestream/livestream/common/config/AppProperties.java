package com.livestream.livestream.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yaml 의 {@code app.*} 설정값 (DESIGN §4.1). 환경변수 {@code APP_…} 로 덮어쓴다.
 * 기본값은 application.yaml 이 단일 출처다.
 */
@ConfigurationProperties("app")
public record AppProperties(
		Grid grid,
		Broadcast broadcast,
		Thumbnail thumbnail,
		Recording recording,
		Uploads uploads,
		Mediamtx mediamtx,
		Toss toss,
		Jwt jwt,
		Seed seed,
		String publicUrl
) {

	/** @param threshold 이 동시 시청자 수부터 recommendedMode=grid */
	public record Grid(int threshold) {
	}

	/**
	 * @param disconnectGraceSeconds       송출 끊김 유예
	 * @param endBlockSeconds              소유자 종료 · 유예 만료 뒤 같은 채널의 publish 를 거절하는 시간
	 * @param viewerBroadcastIntervalSeconds 시청자 수 이벤트 주기
	 */
	public record Broadcast(int disconnectGraceSeconds, int endBlockSeconds, int viewerBroadcastIntervalSeconds) {
	}

	/** @param intervalSeconds 라이브 썸네일 캡처 주기 */
	public record Thumbnail(int intervalSeconds) {
	}

	/**
	 * @param retentionDays 영상 파일 보관 일수
	 * @param dir           MediaMTX 와 공유하는 볼륨(raw/, vod/)
	 */
	public record Recording(int retentionDays, String dir) {
	}

	/** @param dir 썸네일 등 업로드 파일(thumbnails/) */
	public record Uploads(String dir) {
	}

	/**
	 * @param apiUrl        MediaMTX Control API
	 * @param rtspUrl       썸네일 캡처용 RTSP
	 * @param internalToken MediaMTX 훅이 보내는 X-Internal-Token
	 */
	public record Mediamtx(String apiUrl, String rtspUrl, String internalToken) {
	}

	/** 토스페이먼츠 테스트 키 */
	public record Toss(String clientKey, String secretKey) {
	}

	/**
	 * @param secret                      HS 서명 키 (32바이트 이상)
	 * @param accessMinutes               access 토큰 수명(분)
	 * @param refreshDays                 refresh 토큰 수명(일)
	 * @param refreshRotationGraceSeconds 회전된 refresh 토큰을 잠깐 더 받아 주는 시간
	 */
	public record Jwt(String secret, int accessMinutes, int refreshDays, int refreshRotationGraceSeconds) {
	}

	/** @param enabled 시드 데이터 투입 */
	public record Seed(boolean enabled) {
	}
}
