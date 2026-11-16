import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { useAuthDialog } from '../../store/authDialog'
import { Button } from '@astryxdesign/core/Button'
import { IconButton } from '@astryxdesign/core/IconButton'
import { MoreMenu } from '@astryxdesign/core/MoreMenu'
import { ChevronsRight, ExternalLink } from 'lucide-react'
import { useAuth } from '../../store/auth'
import { inputClassName } from '../common/TextField'
import ChatMessage from './ChatMessage'
import { CHAT_MAX_LENGTH, chatInputError, entryKey } from './chatState'
import type { useBroadcastChat } from './useBroadcastChat'

type Chat = ReturnType<typeof useBroadcastChat>

const CONNECTION_TEXT = { connecting: '채팅에 연결하는 중…', reconnecting: '채팅 연결이 끊겨 다시 연결하는 중…' } as const

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
  /** 입력창 아래 줄 왼쪽에 둘 버튼(시청 화면: 후원하기) */
  actions?: ReactNode
}

export default function ChatPanel({ chat, ended = false, className = '', framed = true, onCollapse, onPopout, actions }: Props) {
  const loggedIn = useAuth((state) => state.member !== null)
  const authReady = useAuth((state) => state.ready)
  const openLogin = useAuthDialog((state) => state.open)
  const listRef = useRef<HTMLUListElement>(null)
  const stickRef = useRef(true)
  const [draft, setDraft] = useState('')
  const [inputError, setInputError] = useState('')

  useEffect(() => {
    const list = listRef.current
    if (list && stickRef.current) list.scrollTop = list.scrollHeight
  }, [chat.entries])

  const onScroll = () => {
    const list = listRef.current
    if (list) stickRef.current = list.scrollHeight - list.scrollTop - list.clientHeight < 40
  }

  // 입력창은 비로그인에게도 같은 모양으로 둔다. 보내기를 누르면 로그인 모달을 띄우고 쓴 글은 남겨 둔다(로그인 뒤 다시 보내기)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!authReady) return
    if (!loggedIn) {
      openLogin()
      return
    }
    const problem = chatInputError(draft)
    if (problem) {
      setInputError(problem)
      return
    }
    if (!chat.send(draft)) {
      setInputError('채팅에 연결되지 않아 보내지 못했습니다. 잠시 후 다시 보내 주세요.')
      return
    }
    setInputError('')
    setDraft('')
    stickRef.current = true
  }

  const notice = inputError || chat.chatError?.message
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
      <div className={`p-3 ${framed ? 'border-t border-border' : ''}`}>
        {ended ? (
          <p className="text-center text-sm text-secondary">방송이 종료되어 채팅을 보낼 수 없습니다.</p>
        ) : (
          // 1줄: 입력창(전체 폭) · 2줄: 왼쪽 actions(시청 화면의 후원하기 등) · 오른쪽 보내기
          <form onSubmit={submit} className="space-y-2">
            <label htmlFor="chat-input" className="sr-only">채팅 메시지</label>
            <input
              id="chat-input"
              value={draft}
              onChange={(event) => { setDraft(event.target.value); setInputError(''); chat.clearError() }}
              maxLength={CHAT_MAX_LENGTH * 2}
              autoComplete="off"
              placeholder="채팅을 입력하세요"
              className={`${inputClassName.replace('py-2.5', 'py-2')} text-sm`}
            />
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2">{actions}</div>
              <Button type="submit" label="보내기" variant="primary" size="sm" />
            </div>
          </form>
        )}
        {notice && !ended && <p role="alert" className="mt-2 text-xs text-error">{notice}</p>}
      </div>
    </aside>
  )
}
