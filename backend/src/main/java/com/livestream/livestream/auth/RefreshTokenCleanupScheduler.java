package com.livestream.livestream.auth;

import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * refresh_token 정리 (AUTH-1#2): 매일 04:10(KST) 만료된 지 {@link #RETENTION} 지난 행을 지운다.
 * 회전 · revoke된 행도 {@code expires_at}까지는 남긴다 — 재사용 의심(유예 밖 회전 토큰) 판단에 필요하기 때문이다.
 * 만료 직후 며칠은 "만료(TOKEN_EXPIRED)"와 "없음(TOKEN_INVALID)"을 구분해 줄 수 있도록 남겨 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupScheduler {

	static final Duration RETENTION = Duration.ofDays(1);

	private final RefreshTokenRepository refreshTokenRepository;
	private final TransactionTemplate tx;

	@Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
	public void run() {
		int deleted = purgeExpired(Instant.now());
		if (deleted > 0) {
			log.info("만료된 refresh 토큰 {}건 삭제", deleted);
		}
	}

	/** @param now 기준 시각(테스트에서 주입) */
	public int purgeExpired(Instant now) {
		Integer deleted = tx.execute(s -> refreshTokenRepository.deleteExpiredBefore(now.minus(RETENTION)));
		return deleted == null ? 0 : deleted;
	}
}
