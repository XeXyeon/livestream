package com.livestream.livestream.recording;

import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.tx.AfterCommit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 다시보기 영상 보관 (DESIGN §4.10): 매일 04:00(KST) {@code READY AND created_at < now - retention-days} →
 * FILE_DELETED + file_deleted_at, 커밋 뒤 영상 파일 삭제({@code vod/} · {@code private/} 둘 다 본다). 기록(방송 · 댓글)은 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingRetentionScheduler {

	private final RecordingRepository recordingRepository;
	private final RecordingFiles files;
	private final AppProperties props;
	private final TransactionTemplate tx;

	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	public void run() {
		int purged = purgeExpired(Instant.now());
		if (purged > 0) {
			log.info("보관 기간이 지난 다시보기 {}건 영상 삭제", purged);
		}
	}

	/** @param now 기준 시각(테스트에서 주입) */
	public int purgeExpired(Instant now) {
		Instant before = now.minus(Duration.ofDays(props.recording().retentionDays()));
		List<Long> ids = recordingRepository.findReadyCreatedBefore(before);
		int purged = 0;
		for (Long id : ids) {
			Boolean done = tx.execute(s -> recordingRepository.findByIdForUpdate(id)
					.filter(r -> r.getStatus() == RecordingStatus.READY && r.getCreatedAt().isBefore(before))
					.map(r -> {
						String filePath = r.getFilePath();
						r.markFileDeleted(now);
						AfterCommit.run("보관 만료 영상 삭제", () -> files.deleteVideoQuietly(filePath));
						return true;
					}).orElse(false));
			if (Boolean.TRUE.equals(done)) {
				purged++;
			}
		}
		return purged;
	}
}
