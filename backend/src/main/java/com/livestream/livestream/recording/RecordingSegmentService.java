package com.livestream.livestream.recording;

import com.livestream.livestream.common.tx.AfterCommit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 녹화 조각 수집 (DESIGN §4.10 "조각 수집"): MediaMTX {@code runOnRecordSegmentComplete} 훅 → 채널의 가장 최근 방송 recording 에
 * {@code recording_segment}(sequence = count+1) 추가.
 * <ul>
 *   <li>RECORDING · PROCESSING(방송 종료 직후 — kick 뒤 조각이 닫히며 훅이 종료 이벤트보다 늦게 올 수 있다)이면 붙인다.</li>
 *   <li>방송이 삭제됐거나 FILE_DELETED 면 조각 파일을 바로 지운다.</li>
 *   <li>그 외(READY · FAILED — 병합 뒤 늦게 온 조각)는 파일을 남기고 로그만.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingSegmentService {

	private final RecordingRepository recordingRepository;
	private final RecordingSegmentRepository segmentRepository;
	private final RecordingFiles files;

	/**
	 * READ_COMMITTED: 같은 조각 훅이 동시에 두 번 오면 둘째는 recording 잠금을 기다렸다가 첫째가 커밋한 조각 행을 봐야 한다
	 * (REPEATABLE_READ 면 잠금 전 스냅숏으로 읽어 같은 조각을 한 번 더 넣는다). 유니크(recording_id, file_path)가 마지막 방어선.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void onSegmentComplete(Long channelId, String segmentPath) {
		String relative = RecordingFiles.toRelativeRawSegment(segmentPath, channelId);
		if (relative == null) {
			log.warn("segment-complete 훅: 해석할 수 없는 조각 경로: channel={}", channelId);
			return;
		}
		List<Long> latest = recordingRepository.findLatestIdByChannel(channelId, PageRequest.of(0, 1));
		if (latest.isEmpty()) {
			log.warn("segment-complete 훅: 방송을 찾지 못함(조각 파일은 남김): channel={} segment={}", channelId, relative);
			return;
		}
		// id 만 먼저 읽고 엔티티는 잠금 읽기로 처음 올린다(먼저 올려 두면 잠금 뒤에도 옛 값이 남는다).
		Recording recording = recordingRepository.findByIdForUpdate(latest.getFirst()).orElseThrow();
		RecordingStatus status = recording.getStatus();
		if (recording.getBroadcast().isDeleted() || status == RecordingStatus.FILE_DELETED) {
			log.info("segment-complete 훅: 삭제된 방송의 조각이라 지움: recording={} segment={}", recording.getId(), relative);
			AfterCommit.run("삭제된 방송의 녹화 조각 삭제", () -> files.deleteQuietly(relative));
			return;
		}
		if (status != RecordingStatus.RECORDING && status != RecordingStatus.PROCESSING) {
			log.warn("segment-complete 훅: 병합 뒤 늦게 온 조각(파일은 남김): recording={} status={} segment={}",
					recording.getId(), status, relative);
			return;
		}
		if (segmentRepository.existsByRecordingIdAndFilePath(recording.getId(), relative)) {
			return; // 같은 훅이 두 번 왔다
		}
		int sequence = (int) segmentRepository.countByRecordingId(recording.getId()) + 1;
		segmentRepository.save(RecordingSegment.of(recording, relative, sequence));
		log.info("녹화 조각 추가: recording={} seq={} segment={}", recording.getId(), sequence, relative);
	}
}
