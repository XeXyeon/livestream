package com.livestream.livestream.mediamtx;

import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MediaMTX 인증 콜백 (DESIGN §4.9). publish 만 스트림 키로 검사하고 read · playback 은 허용한다.
 * X-Internal-Token 대상이 아니며(MediaMTX가 헤더를 못 붙임), Caddy 가 바깥에 노출하지 않는다.
 */
@Slf4j
@Tag(name = "Internal (MediaMTX)", description = "MediaMTX 인증 콜백 · 훅. 외부 비공개")
@RestController
@RequestMapping("/api/internal/mediamtx")
@RequiredArgsConstructor
public class MediaMtxAuthController {

	private final BroadcastLifecycleService lifecycle;

	@Operation(summary = "MediaMTX 인증 콜백", description = "publish: 스트림 키 일치 + 종료 뒤 차단 아님 → 200, 아니면 401. read · playback: 200")
	@PostMapping("/auth")
	public ResponseEntity<Void> auth(@RequestBody MediaMtxAuthRequest req) {
		String action = req.action() == null ? "" : req.action();
		return switch (action) {
			case "read", "playback" -> ResponseEntity.ok().build();
			case "publish" -> canPublish(req) ? ResponseEntity.ok().build()
					: ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
			default -> ResponseEntity.notFound().build();
		};
	}

	private boolean canPublish(MediaMtxAuthRequest req) {
		Long channelId = MediaMtxPaths.channelIdOf(req.path());
		if (channelId == null) {
			log.info("publish 거절(경로 형식): protocol={} ip={}", req.protocol(), req.ip());
			return false;
		}
		if (!MediaMtxPaths.isSafeQuery(req.query())) {
			log.warn("publish 거절(허용하지 않는 쿼리 문자): channel={} protocol={} ip={}", channelId, req.protocol(), req.ip());
			return false;
		}
		String key = firstNonEmpty(req.password(), req.token(), MediaMtxPaths.queryParam(req.query(), "pass"));
		boolean allowed = lifecycle.canPublish(channelId, key);
		// 키가 어느 칸으로 들어왔는지는 수동 확인(§8 Bearer 위험)에 필요해 칸 이름만 남긴다. 키 값은 남기지 않는다.
		log.info("publish {}: channel={} protocol={} ip={} keyFrom={}", allowed ? "허용" : "거절", channelId,
				req.protocol(), req.ip(), keySource(req));
		return allowed;
	}

	private static String keySource(MediaMtxAuthRequest req) {
		if (notEmpty(req.password())) {
			return "password";
		}
		if (notEmpty(req.token())) {
			return "token";
		}
		return notEmpty(MediaMtxPaths.queryParam(req.query(), "pass")) ? "query" : "none";
	}

	private static String firstNonEmpty(String... values) {
		for (String v : values) {
			if (notEmpty(v)) {
				return v;
			}
		}
		return null;
	}

	private static boolean notEmpty(String s) {
		return s != null && !s.isEmpty();
	}
}
