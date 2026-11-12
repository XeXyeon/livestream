import { Users } from 'lucide-react'
import { formatCount } from '../broadcast/format'
import type { SessionPhase } from './useBroadcastSession'

const LABEL: Record<SessionPhase, { text: string; className: string }> = {
  idle: { text: '대기', className: 'bg-slate-700 text-slate-200' },
  starting: { text: '연결 중', className: 'bg-amber-500 text-black' },
  live: { text: 'LIVE', className: 'bg-rose-600 text-white' },
  reconnecting: { text: '재연결 중', className: 'bg-amber-500 text-black' },
  ending: { text: '종료 중', className: 'bg-slate-600 text-white' },
  ended: { text: '종료됨', className: 'bg-slate-700 text-slate-200' },
  error: { text: '오류', className: 'bg-rose-900 text-rose-100' },
}

export default function SessionStatus({ phase, viewerCount }: { phase: SessionPhase; viewerCount: number | null }) {
  const label = LABEL[phase]
  return (
    <div role="status" aria-live="polite" className="flex items-center gap-3">
      <span className={`rounded px-2 py-1 text-xs font-bold ${label.className}`}>{label.text}</span>
      {phase === 'live' && viewerCount !== null && (
        <span className="flex items-center gap-1 text-sm text-slate-300"><Users aria-hidden className="h-4 w-4" />시청자 {formatCount(viewerCount)}</span>
      )}
    </div>
  )
}
