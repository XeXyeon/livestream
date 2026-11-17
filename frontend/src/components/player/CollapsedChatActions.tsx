import { HandCoins, MessageSquare } from 'lucide-react'
import { Tooltip } from '@astryxdesign/core/Tooltip'
import { useAuth } from '../../store/auth'
import { useAuthDialog } from '../../store/authDialog'

// 채팅 열을 접었을 때 플레이어 우측 상단에 놓는 아이콘 둘: 채팅(다시 펼치기) · 후원(DonateDialog 열기, 비로그인은 로그인 모달).
// 컨트롤 줄과 같은 반투명 검정 · 흰 아이콘이라 어느 영상 위에서도 보이고, 컨트롤이 숨어도 남는다(펼칠 방법이 사라지면 안 되므로).
// 후원은 onDonate가 없으면(종료 · 방송인 본인) 그리지 않는다. 호출하는 쪽이 lg 이상 · 전체 화면 아님을 정한다.
const buttonClass =
  'inline-flex h-10 w-10 items-center justify-center rounded-full bg-black/55 text-white backdrop-blur-sm transition hover:bg-black/70 focus-visible:outline-2 focus-visible:outline-white'

export default function CollapsedChatActions({ onExpandChat, onDonate }: { onExpandChat: () => void; onDonate?: () => void }) {
  const loggedIn = useAuth((state) => state.member !== null)
  const authReady = useAuth((state) => state.ready)
  const openLogin = useAuthDialog((state) => state.open)
  return (
    <div className="flex items-center gap-2">
      <Tooltip content="채팅" placement="below">
        <button type="button" aria-label="채팅 펼치기" onClick={onExpandChat} className={buttonClass}>
          <MessageSquare aria-hidden className="h-5 w-5" />
        </button>
      </Tooltip>
      {onDonate && authReady && (
        <Tooltip content="후원" placement="below">
          <button type="button" aria-label="후원" onClick={() => (loggedIn ? onDonate() : openLogin())} className={buttonClass}>
            <HandCoins aria-hidden className="h-5 w-5" />
          </button>
        </Tooltip>
      )}
    </div>
  )
}
