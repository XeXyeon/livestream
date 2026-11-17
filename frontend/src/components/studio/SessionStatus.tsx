import { Users } from 'lucide-react'
import { Badge } from '@astryxdesign/core/Badge'
import type { BadgeVariant } from '@astryxdesign/core/Badge'
import { formatCount } from '../broadcast/format'
import type { SessionPhase } from './useBroadcastSession'

const LABEL: Record<SessionPhase, { text: string; variant: BadgeVariant }> = {
  idle: { text: '송출 전', variant: 'neutral' },
  starting: { text: '연결 중', variant: 'warning' },
  live: { text: 'LIVE', variant: 'error' },
  reconnecting: { text: '재연결 중', variant: 'warning' },
  ending: { text: '종료 중', variant: 'neutral' },
  ended: { text: '종료됨', variant: 'neutral' },
  error: { text: '오류', variant: 'error' },
}

type Props = {
  phase: SessionPhase
  viewerCount: number | null
  /** 미리보기 위에 겹칠 때: 어두운 바탕 위에서 읽히도록 반투명 검정 판 + 흰 글자 */
  overlay?: boolean
}

export default function SessionStatus({ phase, viewerCount, overlay = false }: Props) {
  const label = LABEL[phase]
  return (
    <div role="status" aria-live="polite" className={overlay ? 'flex items-center gap-2 rounded-md bg-black/60 px-2 py-1' : 'flex items-center gap-3'}>
      <Badge variant={label.variant} label={label.text} />
      {phase === 'live' && viewerCount !== null && (
        <span className={`flex items-center gap-1 text-sm ${overlay ? 'text-white' : 'text-secondary'}`}><Users aria-hidden className="h-4 w-4" />시청자 {formatCount(viewerCount)}</span>
      )}
    </div>
  )
}
