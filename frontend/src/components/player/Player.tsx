import { useState } from 'react'
import type { ReactNode } from 'react'
import type { BroadcastDetail, BroadcastStatus, PlayerMode } from '../../api/types'
import type { GridStats } from '../../media/gridStats'
import { useIsFullscreen } from '../common/useMediaQuery'
import EndedView from './EndedView'
import GridView from './GridView'
import ModeToggle from './ModeToggle'
import PlayerStats from './PlayerStats'
import WhepView from './WhepView'

type Props = {
  broadcast: BroadcastDetail
  initialMode?: PlayerMode
  /** 영화관 모드: 부모(Live)가 높이를 정하고 영상이 그 높이를 채운다 */
  theater?: boolean
  onTheaterChange?: (theater: boolean) => void
  /** 영상 바로 아래 한 줄(시청 화면의 방송 제목). 재생 모드 · 통계 줄은 그 아래 */
  title?: ReactNode
}

// §6.5 Player. 처음 모드는 서버의 recommendedMode(시청자 수가 임계값 이상이면 grid), 이후는 토글로 바꾼다
// (바꿀 때 이전 플레이어는 언마운트되며 close). 상태별: DISCONNECTED → 재연결 대기 오버레이, ENDED → 종료 화면.
export default function Player({ broadcast, initialMode, theater = false, onTheaterChange, title }: Props) {
  const fullscreen = useIsFullscreen()
  const fill = theater || fullscreen
  const chrome = { fill, theater, onTheaterChange }
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
    // 채우기(영화관 · 전체 화면): 영상 칸이 남은 높이를 다 쓰고, 전체 화면에서는 모드 줄을 숨긴다
    <div className={fill ? 'flex h-full flex-col' : 'space-y-2'}>
      <div className={`relative overflow-hidden ${fill ? 'min-h-0 flex-1' : 'rounded-lg'}`}>
        {mode === 'whep'
          ? <WhepView url={broadcast.play.whepUrl} status={broadcast.status} chrome={chrome} />
          : <GridView key={gridGeneration} hlsUrl={broadcast.play.hlsUrl} onStats={setStats} onP2pUnavailable={() => setP2p(false)} chrome={chrome} />}
        {broadcast.status === 'DISCONNECTED' && (
          <div role="status" className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/75 px-6 text-center text-slate-100">
            <span aria-hidden className="h-6 w-6 motion-safe:animate-spin motion-reduce:animate-pulse rounded-full border-2 border-slate-300 border-t-transparent" />
            <p className="font-semibold">방송인 연결이 끊겼습니다. 재연결을 기다리는 중…</p>
          </div>
        )}
      </div>
      {/* 제목은 모드 줄과 나눠 혼자 한 줄을 쓴다: 같은 줄에 두면 휴대폰 폭에서 제목 칸이 거의 0이 되어 글자가 한 자씩 꺾였다 */}
      {title && <div className={`min-w-0 ${fullscreen ? 'hidden' : fill ? 'px-4 pt-2' : 'pt-1'}`}>{title}</div>}
      <div className={`flex flex-wrap items-center justify-end gap-2 ${fullscreen ? 'hidden' : fill ? 'px-4 pb-2' : ''}`}>
        {mode === 'grid' && <PlayerStats stats={stats} p2p={p2p} />}
        <ModeToggle mode={mode} onChange={setMode} />
      </div>
    </div>
  )
}
