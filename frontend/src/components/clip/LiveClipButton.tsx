import { useState } from 'react'
import { Button } from '@astryxdesign/core/Button'
import { Scissors } from 'lucide-react'
import { ApiError } from '../../api/client'
import { createLiveDraft } from '../../api/clips'
import type { BroadcastDetail } from '../../api/types'
import { useAuth } from '../../store/auth'
import { useAuthDialog } from '../../store/authDialog'
import ClipDialog from './ClipDialog'
import ClipProgress from './ClipProgress'

/**
 * S2 플레이어 아래 "클립"(§6.7, CLIP-3): 누르면 최근 2분의 초안을 만들고 다이얼로그를 연다(구간은 다이얼로그에서 고른다).
 * LIVE가 아니면 버튼을 숨기지만, 열린 다이얼로그는 방송이 끝나도 닫지 않는다(초안에서 클립 만들기는 종료 뒤에도 된다).
 * 비로그인이 누르면 로그인 모달(시청 화면의 다른 "로그인하고 …" 버튼과 같은 방식).
 * 만든 뒤 상태 알림은 notice 자리(플레이어 아래 한 줄)에 그린다.
 */
export function LiveClipButton({ broadcast, onCreated }: {
  broadcast: Pick<BroadcastDetail, 'id' | 'status' | 'title'>
  onCreated: (clipId: number) => void
}) {
  const loggedIn = useAuth((state) => state.member !== null)
  const authReady = useAuth((state) => state.ready)
  const openLogin = useAuthDialog((state) => state.open)
  const [draftId, setDraftId] = useState<number | null>(null)
  const [isOpen, setIsOpen] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')

  const showButton = broadcast.status === 'LIVE' && authReady
  if (!showButton && draftId === null) return null

  async function create() {
    if (!loggedIn) { openLogin(); return }
    setPending(true)
    setError('')
    try {
      const draft = await createLiveDraft(broadcast.id)
      setDraftId(draft.id)
      setIsOpen(true)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '클립을 만들지 못했습니다.')
    } finally {
      setPending(false)
    }
  }

  return (
    <span className="inline-flex flex-wrap items-center gap-2">
      {showButton && (
        <Button
          label="클립"
          icon={<Scissors aria-hidden className="h-4 w-4" />}
          variant="secondary"
          size="sm"
          isLoading={pending}
          onClick={() => void create()}
        />
      )}
      {error && <span role="alert" className="text-sm text-error">{error}</span>}
      {draftId !== null && (
        <ClipDialog
          mode="live"
          draftId={draftId}
          defaultTitle={broadcast.title}
          isOpen={isOpen}
          onOpenChange={(open) => {
            setIsOpen(open)
            // 닫으면 초안을 놓는다(서버가 보관 시간 뒤 지운다). 다시 누르면 새 초안을 만든다.
            if (!open) setDraftId(null)
          }}
          onCreated={onCreated}
        />
      )}
    </span>
  )
}

/** 시청 화면의 클립 알림 한 줄. 만든 클립이 없으면 아무것도 그리지 않는다 */
export function LiveClipNotice({ clipId, onDismiss }: { clipId: number | null; onDismiss: () => void }) {
  if (clipId === null) return null
  return <ClipProgress key={clipId} clipId={clipId} onDismiss={onDismiss} />
}
