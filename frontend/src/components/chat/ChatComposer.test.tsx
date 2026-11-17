import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import ChatComposer from './ChatComposer'
import type { useBroadcastChat } from './useBroadcastChat'

type Chat = ReturnType<typeof useBroadcastChat>
const chat: Chat = { entries: [], connection: 'connected', chatError: null, send: () => true, clearError: () => {} }

describe('ChatComposer', () => {
  it('overlay: 입력창에 aria-label · enterkeyhint · maxlength, 보내기 아이콘 버튼', () => {
    const html = renderToString(<ChatComposer chat={chat} tone="overlay" />)
    expect(html).toMatch(/<input[^>]*aria-label="채팅 메시지"/)
    expect(html).toContain('enterKeyHint="send"')
    expect(html).toContain('maxLength="400"')
    expect(html).toMatch(/<button[^>]*type="submit"[^>]*aria-label="보내기"/)
    expect(html).toContain('autoComplete="off"')
  })

  it('panel: 입력창 · 보내기 버튼이 있고 actions 슬롯이 보인다', () => {
    const html = renderToString(<ChatComposer chat={chat} tone="panel" actions={<span>후원하기</span>} />)
    expect(html).toMatch(/<input[^>]*aria-label="채팅 메시지"/)
    expect(html).toContain('보내기')
    expect(html).toContain('후원하기')
    expect(html).not.toContain('enterKeyHint')
  })

  it('서버가 준 채팅 오류 문구를 role="alert"로 보인다', () => {
    const html = renderToString(<ChatComposer chat={{ ...chat, chatError: { code: 'CHAT_RATE_LIMITED', message: '너무 빨리 보내고 있습니다.' } }} tone="overlay" />)
    expect(html).toMatch(/<p role="alert"[^>]*>너무 빨리 보내고 있습니다\.<\/p>/)
  })
})
