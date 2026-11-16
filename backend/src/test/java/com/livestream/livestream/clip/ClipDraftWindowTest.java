package com.livestream.livestream.clip;

import static org.assertj.core.api.Assertions.assertThat;

import com.livestream.livestream.clip.ClipProcessingService.DraftWindow;
import com.livestream.livestream.mediamtx.MediaMtxClient.RecordingSegment;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 초안 창을 녹화 조각에 맞추기(DESIGN §4.18 ①, CLIP-3#1). */
class ClipDraftWindowTest {

	private static final Instant STARTED = Instant.parse("2026-11-16T02:38:02.820Z");

	private static RecordingSegment seg(String start, double seconds) {
		return new RecordingSegment(Instant.parse(start), seconds);
	}

	private static DraftWindow align(DraftWindow w, RecordingSegment... segments) {
		return ClipProcessingService.alignToSegments(STARTED, w, List.of(segments));
	}

	@Test
	void 드리프트_리셋으로_겹친_짧은_첫_조각은_버리고_본_조각_시작초_올림으로_옮긴다() {
		// 방송 57: 02:38:02.76(5초) · 02:38:04.81(106초) — 겹친다
		DraftWindow w = align(new DraftWindow(0, 30),
				seg("2026-11-16T02:38:02.760Z", 5), seg("2026-11-16T02:38:04.810Z", 106));
		assertThat(w).isEqualTo(new DraftWindow(2, 28)); // 1.99초 → 2, 끝(30)은 그대로
	}

	@Test
	void 틈이_있어도_앞_조각은_버린다() {
		DraftWindow w = align(new DraftWindow(0, 60),
				seg("2026-11-16T02:38:02.700Z", 3), seg("2026-11-16T02:38:10.000Z", 100));
		assertThat(w).isEqualTo(new DraftWindow(8, 52)); // 7.18초 → 8
	}

	@Test
	void 이어지는_조각_묶음이_창_시작을_덮으면_그대로() {
		DraftWindow w = new DraftWindow(10, 120);
		// 끝 · 시작이 0.5초 안으로 이어진 조각 셋
		assertThat(align(w, seg("2026-11-16T02:38:00.000Z", 60), seg("2026-11-16T02:39:00.200Z", 60),
				seg("2026-11-16T02:40:00.200Z", 300))).isEqualTo(w);
	}

	@Test
	void 창_끝을_덮는_조각에서_거꾸로_끊긴_곳까지만_묶는다() {
		// 0~60 조각과 61.5~ 조각 사이 1.5초 틈 → 뒤 묶음(61.5~)부터
		DraftWindow w = align(new DraftWindow(0, 120),
				seg("2026-11-16T02:38:02.820Z", 60), seg("2026-11-16T02:39:04.320Z", 30), seg("2026-11-16T02:39:34.320Z", 200));
		assertThat(w).isEqualTo(new DraftWindow(62, 58));
	}

	@Test
	void 조각이_없거나_창과_겹치지_않으면_그대로() {
		DraftWindow w = new DraftWindow(5, 30);
		assertThat(align(w)).isEqualTo(w);
		assertThat(align(w, seg("2026-11-16T03:00:00Z", 10))).isEqualTo(w);
	}

	@Test
	void 옮긴_창이_짧으면_길이도_짧아진다() {
		DraftWindow w = align(new DraftWindow(0, 30), seg("2026-11-16T02:38:02.820Z", 3), seg("2026-11-16T02:38:29.000Z", 100));
		assertThat(w).isEqualTo(new DraftWindow(27, 3)); // 처리기가 min-seconds(5) 미만이라 NO_MEDIA 로 끝낸다
	}
}
