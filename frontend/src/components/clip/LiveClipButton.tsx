import { useState } from 'react'
import type { ReactNode } from 'react'
import { Scissors } from 'lucide-react'
import { ApiError } from '../../api/client'
import { createLiveDraft } from '../../api/clips'
import type { BroadcastDetail } from '../../api/types'
import { useAuth } from '../../store/auth'
import { useAuthDialog } from '../../store/authDialog'
import type { PlayerAction } from '../player/VideoControls'
import ClipDialog from './ClipDialog'
import ClipProgress from './ClipProgress'

/**
 * S2 플레이어 컨트롤 줄의 가위 버튼(§6.7, CLIP-3 · FE-12): 누르면 최근 2분의 초안을 만들고 다이얼로그를 연다(구간은 다이얼로그에서 고른다).
 * `action`은 Player의 `actions`에 넘기고(LIVE · 로그인 복구 뒤에만 있다), `element`(오류 문구 · 다이얼로그)는 방송 정보 아래에 그린다.
 * 열린 다이얼로그는 방송이 끝나도 닫지 않는다(초안에서 클립 만들기는 종료 뒤에도 된다).
 * 비로그인이 누르면 로그인 모달(시청 화면의 다른 "로그인하고 …" 버튼과 같은 방식).
 * 방송이 아직 없으면(`null`) 아무것도 하지 않는다(부르는 쪽이 조기 반환 앞에서 훅을 부를 수 있게).
 */
export function useLiveClip(
  broadcast: Pick<BroadcastDetail, 'id' | 'status' | 'title'> | null,
  onCreated: (clipId: number) => void,
): { action: PlayerAction | null; element: ReactNode } {
  const loggedIn = useAuth((state) => state.member !== null)
  const authReady = useAuth((state) => state.ready)
  const openLogin = useAuthDialog((state) => state.open)
  const [draftId, setDraftId] = useState<number | null>(null)
  const [isOpen, setIsOpen] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')

  async function create() {
    if (!broadcast) return
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

  const action: PlayerAction | null = broadcast && broadcast.status === 'LIVE' && authReady
    ? {
        id: 'clip',
        label: '클립 만들기',
        icon: <Scissors aria-hidden className="h-5 w-5" />,
        onClick: () => void create(),
        disabled: pending,
      }
    : null

  const element = (
    <>
      {error && <p role="alert" className="text-sm text-error">{error}</p>}
      {broadcast && draftId !== null && (
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
    </>
  )
  return { action, element }
}

/** 시청 화면의 클립 알림 한 줄. 만든 클립이 없으면 아무것도 그리지 않는다 */
export function LiveClipNotice({ clipId, onDismiss }: { clipId: number | null; onDismiss: () => void }) {
  if (clipId === null) return null
  return <ClipProgress key={clipId} clipId={clipId} onDismiss={onDismiss} />
}
