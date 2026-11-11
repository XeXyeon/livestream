import type { BroadcastDetail, PlayerMode } from '../../api/types'
import EndedView from './EndedView'
import WhepView from './WhepView'

type Props = {
  broadcast: BroadcastDetail
  initialMode?: PlayerMode
}

// §6.5 Player. 상태별 화면: LIVE → 재생, DISCONNECTED → 재연결 대기 오버레이, ENDED → 종료 화면.
// 그리드 모드(ModeToggle · GridView · PlayerStats)는 GRID-2에서 붙인다.
export default function Player({ broadcast }: Props) {
  if (broadcast.status === 'ENDED') return <EndedView broadcast={broadcast} />
  return (
    <div className="relative overflow-hidden rounded-xl">
      <WhepView url={broadcast.play.whepUrl} status={broadcast.status} />
      {broadcast.status === 'DISCONNECTED' && (
        <div role="status" className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-black/75 px-6 text-center text-slate-100">
          <span aria-hidden className="h-6 w-6 animate-spin rounded-full border-2 border-slate-300 border-t-transparent" />
          <p className="font-semibold">방송인 연결이 끊겼습니다. 재연결을 기다리는 중…</p>
        </div>
      )}
    </div>
  )
}
