import { savedPercent } from '../../media/gridStats'
import type { GridStats } from '../../media/gridStats'

const mb = (bytes: number) => (bytes / 1024 / 1024).toFixed(1)

// 그리드 모드 통계: "서버 n MB · 피어 m MB · 절감 k% · 피어 p명"
export default function PlayerStats({ stats, p2p }: { stats: GridStats | null; p2p: boolean }) {
  if (!p2p) return <p className="text-xs text-slate-400">이 기기에서는 피어 공유 없이 재생합니다.</p>
  if (!stats) return null
  return (
    <p aria-live="off" className="text-xs tabular-nums text-slate-400">
      서버 {mb(stats.httpBytes)} MB · 피어 {mb(stats.p2pDownBytes)} MB · 절감 <span className="font-semibold text-emerald-300">{savedPercent(stats)}%</span> · 피어 {stats.peers}명
      {stats.p2pUpBytes > 0 && <> · 나눠 준 양 {mb(stats.p2pUpBytes)} MB</>}
    </p>
  )
}
