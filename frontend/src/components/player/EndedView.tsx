import { Link } from 'react-router-dom'
import type { BroadcastDetail } from '../../api/types'
import RecommendList from '../broadcast/RecommendList'

function recordingLabel(broadcast: BroadcastDetail): string | null {
  const recording = broadcast.recording
  if (!recording || recording.status === 'FAILED') return '다시보기가 없습니다'
  if (recording.status === 'FILE_DELETED') return '보관 기간이 지나 다시보기가 삭제되었습니다'
  if (recording.status !== 'READY') return '다시보기 준비 중'
  if (recording.visibility === 'PRIVATE' && !broadcast.isOwner) return '다시보기가 공개되지 않았습니다'
  return null
}

// §6.5 종료 화면: 안내 · 다시보기 버튼(READY면 활성) · 추천
export default function EndedView({ broadcast }: { broadcast: BroadcastDetail }) {
  const disabledLabel = recordingLabel(broadcast)
  return (
    <div className="flex flex-col items-center gap-6 rounded-xl bg-slate-900 px-6 py-10 text-center">
      <div>
        <p className="text-xl font-bold text-white">방송이 종료되었습니다</p>
        <p className="mt-1 text-sm text-slate-400">{broadcast.channel.name}님의 방송을 시청해 주셔서 감사합니다.</p>
      </div>
      {disabledLabel || !broadcast.recording
        ? <button type="button" disabled className="rounded-lg bg-slate-700 px-4 py-2.5 text-sm font-semibold text-slate-300">{disabledLabel}</button>
        : <Link to={`/videos/${broadcast.recording.id}`} className="rounded-lg bg-indigo-500 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-400">다시보기</Link>}
      <RecommendList broadcastId={broadcast.id} />
    </div>
  )
}
