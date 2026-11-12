import { useState } from 'react'
import type { BroadcastDetail, BroadcastStatus, PlayerMode } from '../../api/types'
import type { GridStats } from '../../media/gridStats'
import EndedView from './EndedView'
import GridView from './GridView'
import ModeToggle from './ModeToggle'
import PlayerStats from './PlayerStats'
import WhepView from './WhepView'

type Props = {
  broadcast: BroadcastDetail
  initialMode?: PlayerMode
}

// §6.5 Player. 처음 모드는 서버의 recommendedMode(시청자 수가 임계값 이상이면 grid), 이후는 토글로 바꾼다
// (바꿀 때 이전 플레이어는 언마운트되며 close). 상태별: DISCONNECTED → 재연결 대기 오버레이, ENDED → 종료 화면.
export default function Player({ broadcast, initialMode }: Props) {
  const [mode, setMode] = useState<PlayerMode>(initialMode ?? broadcast.recommendedMode)
  const [stats, setStats] = useState<GridStats | null>(null)
  const [p2p, setP2p] = useState(true)
  // 그리드는 방송인이 다시 붙으면 재생목록이 새로 시작되므로 플레이어를 새로 만든다(WHEP은 reconnect로 충분).
  const [gridGeneration, setGridGeneration] = useState(0)
  const [prevStatus, setPrevStatus] = useState<BroadcastStatus>(broadcast.status)
  if (prevStatus !== broadcast.status) {
    setPrevStatus(broadcast.status)
    if (prevStatus === 'DISCONNECTED' && broadcast.status === 'LIVE') setGridGeneration(gridGeneration + 1)
  }

  if (broadcast.status === 'ENDED') return <EndedView broadcast={broadcast} />
  return (
    <div className="space-y-2">
      <div className="relative overflow-hidden rounded-lg">
        {mode === 'whep'
          ? <WhepView url={broadcast.play.whepUrl} status={broadcast.status} />
          : <GridView key={gridGeneration} hlsUrl={broadcast.play.hlsUrl} onStats={setStats} onP2pUnavailable={() => setP2p(false)} />}
        {broadcast.status === 'DISCONNECTED' && (
          <div role="status" className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/75 px-6 text-center text-slate-100">
            <span aria-hidden className="h-6 w-6 animate-spin rounded-full border-2 border-slate-300 border-t-transparent" />
            <p className="font-semibold">방송인 연결이 끊겼습니다. 재연결을 기다리는 중…</p>
          </div>
        )}
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <ModeToggle mode={mode} onChange={setMode} />
        {mode === 'grid' && <PlayerStats stats={stats} p2p={p2p} />}
      </div>
    </div>
  )
}
