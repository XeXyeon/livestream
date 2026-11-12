// 그리드 통계 타입 · 계산. 화면(PlayerStats)이 hls.js 청크를 끌어오지 않도록 gridPlayer와 나눠 둔다.
export type GridStats = { httpBytes: number; p2pDownBytes: number; p2pUpBytes: number; peers: number }

/** 받은 전체 바이트 중 피어에게서 받은 비율(%) */
export function savedPercent(stats: GridStats): number {
  const total = stats.httpBytes + stats.p2pDownBytes
  return total === 0 ? 0 : Math.round((stats.p2pDownBytes / total) * 100)
}
