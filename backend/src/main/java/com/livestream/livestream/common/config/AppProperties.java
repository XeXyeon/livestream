package com.livestream.livestream.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
		String publicUrl,
		Auth auth,
		Clip clip
) {

	/** auth 가 없으면(설정이 비었거나 테스트에서 직접 만들 때) 설계 기본값을 쓴다. */
	@ConstructorBinding
	public AppProperties {
		if (auth == null) {
			auth = new Auth(10, 10, 300);
		}
		if (clip == null) {
			clip = new Clip(5, 60, Duration.ofMinutes(2));
		}
	}

	/** auth 설정 없이 만드는 기존 생성자(테스트 · 호환용). */
	public AppProperties(Grid grid, Broadcast broadcast, Thumbnail thumbnail, Recording recording, Uploads uploads,
			Mediamtx mediamtx, Toss toss, Jwt jwt, Seed seed, String publicUrl) {
		this(grid, broadcast, thumbnail, recording, uploads, mediamtx, toss, jwt, seed, publicUrl, null, null);
	}

	/**
	 * 클립(DESIGN §4.18, CLIP-1).
	 *
	 * @param minSeconds 클립 최소 길이(초)
	 * @param maxSeconds 클립 최대 길이(초)
	 * @param timeout    처리(내려받기 · ffmpeg) 타임아웃
	 */
	public record Clip(int minSeconds, int maxSeconds, Duration timeout) {

		public Clip {
			if (minSeconds <= 0 || maxSeconds < minSeconds || timeout == null || timeout.isNegative() || timeout.isZero()) {
				throw new IllegalArgumentException("app.clip.* 값이 올바르지 않습니다(0 < min-seconds <= max-seconds, timeout > 0)");
			}
		}
	}

	/**
	 * 로그인 · 가입 시도 횟수 제한(DESIGN §4.7, AUTH-1#1).
	 *
	 * @param loginMaxFailures     로그인: IP+이메일당 창 안에서 허용하는 실패 횟수
	 * @param signupMaxAttempts    가입: IP당 창 안에서 허용하는 요청 횟수
	 * @param attemptWindowSeconds 고정 창 길이(초)
	 */
	public record Auth(int loginMaxFailures, int signupMaxAttempts, int attemptWindowSeconds) {

		/** 일부만 덮어써서 0이 되면 제한이 조용히 꺼지므로 기동 때 막는다. */
		public Auth {
			if (loginMaxFailures <= 0 || signupMaxAttempts <= 0 || attemptWindowSeconds <= 0) {
				throw new IllegalArgumentException("app.auth.* 값은 모두 1 이상이어야 합니다");
			}
		}
	}

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
	 * @param playbackUrl   재생 서버(녹화 조각을 시간 범위로 내준다, 라이브 클립 §4.18). 내부망 전용
	 */
	public record Mediamtx(String apiUrl, String rtspUrl, String internalToken, String playbackUrl) {

		/** 생성자가 둘이라 바인딩할 쪽을 정한다. */
		@ConstructorBinding
		public Mediamtx {
		}

		/** playback-url 없이 만드는 기존 생성자(테스트 · 호환용). */
		public Mediamtx(String apiUrl, String rtspUrl, String internalToken) {
			this(apiUrl, rtspUrl, internalToken, null);
		}
	}

	/** 토스페이먼츠 테스트 키 */
	public record Toss(String clientKey, String secretKey) {

		/** 설정 덤프 · 로그에 시크릿 키가 찍히지 않게 가린다. */
		@Override
		public String toString() {
			return "Toss[clientKey=" + clientKey + ", secretKey=" + (secretKey == null || secretKey.isBlank() ? "(없음)" : "****") + "]";
		}
	}

	/**
	 * @param secret                      HS 서명 키 (32바이트 이상)
	 * @param accessMinutes               access 토큰 수명(분)
	 * @param refreshDays                 refresh 토큰 수명(일)
	 * @param refreshRotationGraceSeconds 회전된 refresh 토큰을 잠깐 더 받아 주는 시간
	 */
	public record Jwt(String secret, int accessMinutes, int refreshDays, int refreshRotationGraceSeconds) {
	}

	/**
	 * 시연 시드 (DESIGN §5, OPS-4).
	 *
	 * @param enabled    시드 데이터 투입
	 * @param password   시드 계정 두 개의 비밀번호(환경변수 {@code APP_SEED_PASSWORD}). enabled 일 때 비어 있으면 기동 실패
	 * @param samplePath 샘플 다시보기 원본 영상(저장소 {@code infra/seed/sample.mp4})
	 */
	public record Seed(boolean enabled, String password, String samplePath) {
	}
}
