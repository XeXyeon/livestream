import { useState } from 'react'
import { Button } from '@astryxdesign/core/Button'
import { Scissors } from 'lucide-react'
import { ApiError } from '../../api/client'
import { createLiveClip } from '../../api/clips'
import type { BroadcastDetail } from '../../api/types'
import { useAuth } from '../../store/auth'
import { useAuthDialog } from '../../store/authDialog'
import { CLIP_DEFAULT_SECONDS, LIVE_CLIP_LENGTHS } from './clipState'
import ClipProgress from './ClipProgress'

/**
 * S2 플레이어 아래 "클립"(§6.7): 최근 15 · 30 · 60초(기본 30)를 클립으로 만든다. LIVE가 아니면 숨긴다.
 * 비로그인이 누르면 로그인 모달(시청 화면의 다른 "로그인하고 …" 버튼과 같은 방식).
 * 만든 뒤 상태 알림은 notice 자리(플레이어 아래 한 줄)에 그린다.
 */
export function LiveClipButton({ broadcast, onCreated }: { broadcast: Pick<BroadcastDetail, 'id' | 'status'>; onCreated: (clipId: number) => void }) {
  const loggedIn = useAuth((state) => state.member !== null)
  const authReady = useAuth((state) => state.ready)
  const openLogin = useAuthDialog((state) => state.open)
  const [seconds, setSeconds] = useState<number>(CLIP_DEFAULT_SECONDS)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')

  if (broadcast.status !== 'LIVE' || !authReady) return null

  async function create() {
    if (!loggedIn) { openLogin(); return }
    setPending(true)
    setError('')
    try {
      const clip = await createLiveClip(broadcast.id, { durationSeconds: seconds })
      onCreated(clip.id)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '클립을 만들지 못했습니다.')
    } finally {
      setPending(false)
    }
  }

  return (
    <span className="inline-flex flex-wrap items-center gap-2">
      <span className="inline-flex items-center overflow-hidden rounded-md border border-border">
        <label htmlFor={`live-clip-length-${broadcast.id}`} className="sr-only">클립 길이</label>
        <select
          id={`live-clip-length-${broadcast.id}`}
          value={seconds}
          onChange={(event) => setSeconds(Number(event.target.value))}
          className="h-8 bg-body pl-2 pr-1 text-sm text-primary focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent"
        >
          {LIVE_CLIP_LENGTHS.map((length) => <option key={length} value={length}>최근 {length}초</option>)}
        </select>
      </span>
      <Button
        label="클립"
        icon={<Scissors aria-hidden className="h-4 w-4" />}
        variant="secondary"
        size="sm"
        isLoading={pending}
        onClick={() => void create()}
      />
      {error && <span role="alert" className="text-sm text-error">{error}</span>}
    </span>
  )
}

/** 시청 화면의 클립 알림 한 줄. 만든 클립이 없으면 아무것도 그리지 않는다 */
export function LiveClipNotice({ clipId, onDismiss }: { clipId: number | null; onDismiss: () => void }) {
  if (clipId === null) return null
  return <ClipProgress key={clipId} clipId={clipId} onDismiss={onDismiss} />
}
