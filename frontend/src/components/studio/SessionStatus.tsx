import { Users } from 'lucide-react'
import { Badge } from '@astryxdesign/core/Badge'
import type { BadgeVariant } from '@astryxdesign/core/Badge'
import { formatCount } from '../broadcast/format'
import type { SessionPhase } from './useBroadcastSession'

const LABEL: Record<SessionPhase, { text: string; variant: BadgeVariant }> = {
  idle: { text: '대기', variant: 'neutral' },
  starting: { text: '연결 중', variant: 'warning' },
  live: { text: 'LIVE', variant: 'error' },
  reconnecting: { text: '재연결 중', variant: 'warning' },
  ending: { text: '종료 중', variant: 'neutral' },
  ended: { text: '종료됨', variant: 'neutral' },
  error: { text: '오류', variant: 'error' },
}

export default function SessionStatus({ phase, viewerCount }: { phase: SessionPhase; viewerCount: number | null }) {
  const label = LABEL[phase]
  return (
    <div role="status" aria-live="polite" className="flex items-center gap-3">
      <Badge variant={label.variant} label={label.text} />
      {phase === 'live' && viewerCount !== null && (
        <span className="flex items-center gap-1 text-sm text-secondary"><Users aria-hidden className="h-4 w-4" />시청자 {formatCount(viewerCount)}</span>
      )}
    </div>
  )
}
