import { useRef, useState } from 'react'
import type { ReactNode } from 'react'
import ChatComposer from './ChatComposer'
import type { useBroadcastChat } from './useBroadcastChat'
import { useKeyboardBottom } from './useKeyboardBottom'

type Chat = ReturnType<typeof useBroadcastChat>

/** 입력 줄이 차지하는 높이(위 여백 포함). 본문 아래에 이만큼 비워 마지막 내용이 가려지지 않게 한다 */
export const MOBILE_COMPOSER_SPACE = 'h-[calc(4rem+env(safe-area-inset-bottom))]'

/**
 * 시청 화면(lg 미만)의 채팅 입력 줄(§6.7 S2 시청 화면 정리 ②). 화면 맨 아래에 고정하고(`fixed bottom-0`),
 * 입력창에 포커스가 있는 동안은 키보드 바로 위로 올린다(useKeyboardBottom). 모양은 ChatComposer panel 그대로(한 줄).
 * 입력창은 16px: iOS Safari는 그보다 작은 입력창에 포커스하면 화면을 확대한다.
 */
export default function MobileComposerBar({ chat, actions, onSent }: { chat: Chat; actions?: ReactNode; onSent?: () => void }) {
  const barRef = useRef<HTMLDivElement>(null)
  const [focused, setFocused] = useState(false)
  useKeyboardBottom(barRef, focused)
  return (
    <div
      ref={barRef}
      className={`fixed inset-x-0 bottom-0 z-30 border-t border-border bg-surface px-3 pt-2 [&_input]:text-base ${focused ? 'pb-2' : 'pb-[max(0.5rem,env(safe-area-inset-bottom))]'} lg:hidden`}
    >
      <ChatComposer chat={chat} actions={actions} tone="panel" onFocusChange={setFocused} onSent={onSent} />
    </div>
  )
}
