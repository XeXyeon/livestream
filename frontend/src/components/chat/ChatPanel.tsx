import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../../store/auth'
import ChatMessage from './ChatMessage'
import { CHAT_MAX_LENGTH, chatInputError, entryKey } from './chatState'
import type { useBroadcastChat } from './useBroadcastChat'

type Chat = ReturnType<typeof useBroadcastChat>

const CONNECTION_TEXT = { connecting: '채팅에 연결하는 중…', reconnecting: '채팅 연결이 끊겨 다시 연결하는 중…' } as const

// §6.7 S2 · S3 ChatPanel. 목록 + 입력창. 비로그인은 입력창 대신 "로그인하고 채팅하기", 방송이 끝나면 입력을 막는다.
// 아래에 붙어 있을 때만 새 메시지로 자동 스크롤한다(위로 올려 읽는 중이면 그대로).
export default function ChatPanel({ chat, ended = false, className = '' }: { chat: Chat; ended?: boolean; className?: string }) {
  const loggedIn = useAuth((state) => state.member !== null)
  const authReady = useAuth((state) => state.ready)
  const location = useLocation()
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

  const submit = (event: FormEvent) => {
    event.preventDefault()
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
    <aside aria-label="채팅" className={`flex min-h-0 flex-col rounded-xl border border-slate-800 bg-slate-900 ${className}`}>
      <h2 className="border-b border-slate-800 px-4 py-2 text-sm font-semibold text-slate-200">채팅</h2>
      {chat.connection !== 'connected' && (
        <p role="status" className="px-4 pt-2 text-xs text-amber-300">{CONNECTION_TEXT[chat.connection]}</p>
      )}
      <ul ref={listRef} onScroll={onScroll} aria-live="polite" className="min-h-0 flex-1 space-y-1 overflow-y-auto px-2 py-2 text-sm">
        {chat.entries.length === 0 && chat.connection === 'connected' && (
          <li className="px-2 py-4 text-center text-slate-500">아직 채팅이 없습니다.</li>
        )}
        {chat.entries.map((entry) => <ChatMessage key={entryKey(entry)} entry={entry} />)}
      </ul>
      <div className="border-t border-slate-800 p-3">
        {!authReady ? null : ended ? (
          <p className="text-center text-sm text-slate-500">방송이 종료되어 채팅을 보낼 수 없습니다.</p>
        ) : loggedIn ? (
          <form onSubmit={submit} className="flex gap-2">
            <label htmlFor="chat-input" className="sr-only">채팅 메시지</label>
            <input
              id="chat-input"
              value={draft}
              onChange={(event) => { setDraft(event.target.value); setInputError(''); chat.clearError() }}
              maxLength={CHAT_MAX_LENGTH * 2}
              autoComplete="off"
              placeholder="채팅을 입력하세요"
              className="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-indigo-400 focus:outline-none"
            />
            <button type="submit" className="rounded-lg bg-indigo-600 px-3 py-2 text-sm font-semibold text-white hover:bg-indigo-500">보내기</button>
          </form>
        ) : (
          <Link
            to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`}
            className="block rounded-lg border border-slate-700 py-2 text-center text-sm font-medium text-indigo-300 hover:border-indigo-400"
          >
            로그인하고 채팅하기
          </Link>
        )}
        {notice && !ended && <p role="alert" className="mt-2 text-xs text-rose-300">{notice}</p>}
      </div>
    </aside>
  )
}
