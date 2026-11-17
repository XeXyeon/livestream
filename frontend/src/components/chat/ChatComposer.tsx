import { useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { Send } from 'lucide-react'
import { useAuth } from '../../store/auth'
import { useAuthDialog } from '../../store/authDialog'
import { inputClassName } from '../common/TextField'
import { CHAT_MAX_LENGTH, chatInputError } from './chatState'
import type { useBroadcastChat } from './useBroadcastChat'

type Chat = ReturnType<typeof useBroadcastChat>

type Props = {
  chat: Chat
  /** panel 모양에서 입력창 왼쪽에 둘 아이콘 버튼(시청 화면: 후원하기). overlay에서는 쓰지 않는다 */
  actions?: ReactNode
  /** panel: 한 줄(왼쪽 actions · 입력창 · 입력창 안 오른쪽 끝 보내기 아이콘). overlay: 어두운 화면 위 한 줄 알약 + 보내기 아이콘 */
  tone: 'panel' | 'overlay'
  /** 입력창 포커스가 들어오고 빠질 때(모바일 스튜디오가 키보드 동안 조작 줄을 숨긴다) */
  onFocusChange?: (focused: boolean) => void
  /** 보내기에 성공했을 때(목록이 맨 아래로 붙어 따라가게 하는 데 쓴다) */
  onSent?: () => void
}

// 채팅 입력(§6.7 S2 · S3 ChatPanel, S4 모바일 스튜디오). 비로그인도 같은 입력창을 보고, 보내기를 누르면 로그인 모달이 뜬다(쓴 글은 남겨 둔다).
// 검사 실패 · 전송 실패 문구는 입력창 가까이에 role="alert"로 보인다(panel은 아래, overlay는 위).
export default function ChatComposer({ chat, actions, tone, onFocusChange, onSent }: Props) {
  const loggedIn = useAuth((state) => state.member !== null)
  const authReady = useAuth((state) => state.ready)
  const openLogin = useAuthDialog((state) => state.open)
  const inputRef = useRef<HTMLInputElement>(null)
  const [draft, setDraft] = useState('')
  const [inputError, setInputError] = useState('')

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
    onSent?.()
    // 모바일: 보낸 뒤에도 키보드가 닫히지 않게 입력창에 포커스를 둔다(이어서 칠 수 있다)
    if (tone === 'overlay') inputRef.current?.focus()
  }

  const overlay = tone === 'overlay'
  const notice = inputError || chat.chatError?.message
  const noticeEl = notice && (
    <p role="alert" className={overlay ? 'mb-2 rounded-lg bg-black/60 px-3 py-1 text-xs text-rose-200' : 'mt-2 text-xs text-error'}>{notice}</p>
  )

  const input = (
    <input
      ref={inputRef}
      aria-label="채팅 메시지"
      value={draft}
      onChange={(event) => { setDraft(event.target.value); setInputError(''); chat.clearError() }}
      onFocus={() => onFocusChange?.(true)}
      onBlur={() => onFocusChange?.(false)}
      maxLength={CHAT_MAX_LENGTH * 2}
      autoComplete="off"
      enterKeyHint={overlay ? 'send' : undefined}
      placeholder="채팅을 입력하세요"
      // overlay는 16px(text-base): iOS Safari는 그보다 작은 입력창에 포커스하면 화면을 확대한다
      className={overlay
        ? 'min-w-0 flex-1 rounded-full bg-white/15 px-4 py-2.5 text-base text-white placeholder:text-slate-300 focus:outline-2 focus:outline-white/70'
        : `${inputClassName.replace('py-2.5', 'py-2')} pr-10 text-sm`}
    />
  )

  if (overlay) {
    return (
      <>
        {noticeEl}
        <form onSubmit={submit} className="flex items-center gap-2">
          {input}
          {/* mousedown을 막아 버튼을 눌러도 입력창 포커스(= 키보드)가 빠지지 않게 한다 */}
          <button
            type="submit"
            aria-label="보내기"
            onMouseDown={(event) => event.preventDefault()}
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-white/20 text-white"
          >
            <Send aria-hidden className="h-5 w-5" />
          </button>
        </form>
      </>
    )
  }

  // 한 줄: 왼쪽 actions(시청 화면의 후원 아이콘 등) · 입력창 · 입력창 안 오른쪽 끝 보내기 아이콘
  return (
    <>
      <form onSubmit={submit} className="flex items-center gap-2">
        {actions && <div className="flex shrink-0 items-center gap-1">{actions}</div>}
        <div className="relative min-w-0 flex-1">
          {input}
          {/* mousedown을 막아 버튼을 눌러도 입력창 포커스(= 모바일 키보드)가 빠지지 않게 한다 */}
          <button
            type="submit"
            aria-label="보내기"
            title="보내기"
            onMouseDown={(event) => event.preventDefault()}
            className="absolute right-1 top-1/2 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full text-accent hover:bg-surface focus-visible:outline-2 focus-visible:outline-accent"
          >
            <Send aria-hidden className="h-4 w-4" />
          </button>
        </div>
      </form>
      {noticeEl}
    </>
  )
}
