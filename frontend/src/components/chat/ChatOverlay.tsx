import { useEffect, useRef } from 'react'
import { CONNECTION_TEXT, entryKey } from './chatState'
import type { ChatEntry } from './chatState'
import type { useBroadcastChat } from './useBroadcastChat'

type Chat = ReturnType<typeof useBroadcastChat>

// 어두운 바탕(카메라 미리보기) 위 흰 글자라 ChatMessage의 토큰 색을 쓰지 않고 줄 모양을 따로 둔다(§6.7 S4).
function OverlayLine({ entry }: { entry: ChatEntry }) {
  if (entry.type === 'donation') {
    return (
      <li data-kind="donation" className="w-fit max-w-full break-words rounded-2xl bg-amber-500/60 px-3 py-1.5 text-sm text-white backdrop-blur-sm">
        <span className="font-bold">{entry.donor.nickname}</span>{' '}
        <span className="font-bold">{entry.amount.toLocaleString('ko-KR')}원</span> 후원
        {entry.message && <span className="block whitespace-pre-wrap">{entry.message}</span>}
      </li>
    )
  }
  const owner = entry.member.isChannelOwner
  return (
    <li data-kind="chat" className="w-fit max-w-full break-words rounded-2xl bg-black/45 px-3 py-1.5 text-sm text-white backdrop-blur-sm">
      {owner && <span className="mr-1 rounded bg-sky-500/80 px-1.5 py-0.5 text-xs font-semibold align-middle">방송인</span>}
      <span className={`mr-1.5 font-bold ${owner ? 'text-sky-300' : ''}`}>{entry.member.nickname}</span>
      <span className="whitespace-pre-wrap">{entry.content}</span>
    </li>
  )
}

type Props = {
  chat: Chat
  /** true면 목록을 그리지 않는다(연결은 그대로 유지) */
  hidden?: boolean
  className?: string
}

/**
 * §6.7 S4 모바일 스튜디오 채팅 오버레이. 바탕은 투명하고 줄마다 반투명 알약. 최근 메시지가 아래에 쌓인다.
 * 아래에 붙어 있을 때만 새 메시지로 자동 스크롤한다(위로 올려 읽는 중이면 그대로, ChatPanel과 같은 규칙).
 */
export default function ChatOverlay({ chat, hidden = false, className = '' }: Props) {
  const listRef = useRef<HTMLUListElement>(null)
  const stickRef = useRef(true)

  // 다시 보이게 했을 때(hidden 해제)도 맨 아래로 맞춘다
  useEffect(() => {
    if (hidden) {
      stickRef.current = true
      return
    }
    const list = listRef.current
    if (list && stickRef.current) list.scrollTop = list.scrollHeight
  }, [chat.entries, hidden])

  if (hidden) return null

  const onScroll = () => {
    const list = listRef.current
    if (list) stickRef.current = list.scrollHeight - list.scrollTop - list.clientHeight < 40
  }

  return (
    <div className={className}>
      {chat.connection !== 'connected' && (
        <p role="status" className="mb-1 w-fit rounded-2xl bg-black/45 px-3 py-1 text-xs text-amber-300 backdrop-blur-sm">{CONNECTION_TEXT[chat.connection]}</p>
      )}
      <ul ref={listRef} onScroll={onScroll} aria-live="polite" aria-label="채팅" className="max-h-[32dvh] space-y-1 overflow-y-auto overscroll-contain">
        {chat.entries.map((entry) => <OverlayLine key={entryKey(entry)} entry={entry} />)}
      </ul>
    </div>
  )
}
