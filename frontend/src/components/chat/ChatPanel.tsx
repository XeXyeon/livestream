import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { IconButton } from '@astryxdesign/core/IconButton'
import { MoreMenu } from '@astryxdesign/core/MoreMenu'
import { ChevronsRight, ExternalLink } from 'lucide-react'
import ChatComposer from './ChatComposer'
import ChatMessage from './ChatMessage'
import { CONNECTION_TEXT, entryKey } from './chatState'
import type { useBroadcastChat } from './useBroadcastChat'

type Chat = ReturnType<typeof useBroadcastChat>

// §6.7 S2 · S3 ChatPanel. 목록 + 입력창. 비로그인도 같은 입력창을 보고, 보내기를 누르면 로그인 모달이 뜬다. 방송이 끝나면 입력을 막는다.
// 아래에 붙어 있을 때만 새 메시지로 자동 스크롤한다(위로 올려 읽는 중이면 그대로).
type Props = {
  chat: Chat
  ended?: boolean
  /** 높이 · 바탕 등 바깥 모양. framed=false면 테두리 · 안쪽 구분선 없이 바탕색만(사이드바와 같은 결) */
  className?: string
  framed?: boolean
  /** 있으면 제목 줄에 "채팅 접기" 버튼(lg 이상에서만 보임) */
  onCollapse?: () => void
  /** 있으면 더보기 메뉴에 "새 창으로 채팅 열기" */
  onPopout?: () => void
  /** 입력 줄 왼쪽에 둘 아이콘 버튼(시청 화면: 후원하기) */
  actions?: ReactNode
  /** 입력 줄을 패널 안에 둘지(`inside`, 기본) 뺄지(`none`: 시청 화면의 lg 미만은 화면 하단 고정 줄이 맡는다). 방송이 끝났다는 문구는 `none`에서도 패널 안에 남는다 */
  composer?: 'inside' | 'none'
  /** 값이 바뀌면 목록을 맨 아래로 붙여 따라가게 한다(패널 밖 입력 줄에서 보냈을 때 올린다) */
  stickSignal?: number
}

export default function ChatPanel({ chat, ended = false, className = '', framed = true, onCollapse, onPopout, actions, composer = 'inside', stickSignal }: Props) {
  const listRef = useRef<HTMLUListElement>(null)
  const stickRef = useRef(true)

  useEffect(() => { stickRef.current = true }, [stickSignal])

  useEffect(() => {
    const list = listRef.current
    if (list && stickRef.current) list.scrollTop = list.scrollHeight
  }, [chat.entries])

  const onScroll = () => {
    const list = listRef.current
    if (list) stickRef.current = list.scrollHeight - list.scrollTop - list.clientHeight < 40
  }

  return (
    <aside aria-label="채팅" className={`flex min-h-0 flex-col ${framed ? 'rounded-lg border border-border bg-surface' : ''} ${className}`}>
      {/* 머리줄: 왼쪽 접기 · 가운데 제목 · 오른쪽 더보기(새 창). 양쪽 칸 폭을 같게 둬 제목이 가운데에 온다 */}
      <div className={`grid h-11 shrink-0 grid-cols-[2.5rem_1fr_2.5rem] items-center px-1 ${framed ? 'border-b border-border' : ''}`}>
        <div className="flex justify-center">
          {onCollapse && (
            <IconButton label="채팅 접기" icon={<ChevronsRight aria-hidden className="h-4 w-4" />} variant="ghost" size="sm" onClick={onCollapse} className="hidden lg:inline-flex" />
          )}
        </div>
        <h2 className="text-center text-sm font-semibold text-primary">채팅</h2>
        <div className="flex justify-center">
          {onPopout && (
            <MoreMenu
              label="채팅 메뉴"
              size="sm"
              placement="below"
              items={[{ label: '새 창으로 채팅 열기', icon: <ExternalLink aria-hidden className="h-4 w-4" />, onClick: onPopout }]}
            />
          )}
        </div>
      </div>
      {chat.connection !== 'connected' && (
        <p role="status" className="px-4 pt-2 text-xs text-warning">{CONNECTION_TEXT[chat.connection]}</p>
      )}
      <ul ref={listRef} onScroll={onScroll} aria-live="polite" className="min-h-0 flex-1 space-y-1 overflow-y-auto overscroll-contain px-2 py-2 text-sm">
        {chat.entries.length === 0 && chat.connection === 'connected' && (
          <li className="px-2 py-4 text-center text-secondary">아직 채팅이 없습니다.</li>
        )}
        {chat.entries.map((entry) => <ChatMessage key={entryKey(entry)} entry={entry} />)}
      </ul>
      {(composer === 'inside' || ended) && (
        <div className={`p-3 ${framed ? 'border-t border-border' : ''}`}>
          {ended
            ? <p className="text-center text-sm text-secondary">방송이 종료되어 채팅을 보낼 수 없습니다.</p>
            : <ChatComposer chat={chat} actions={actions} tone="panel" onSent={() => { stickRef.current = true }} />}
        </div>
      )}
    </aside>
  )
}
