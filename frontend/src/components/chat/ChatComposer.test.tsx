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

  it('panel: 한 줄 — 왼쪽 actions, 입력창, 입력창 안 보내기 아이콘 버튼(글자 버튼 없음)', () => {
    const html = renderToString(<ChatComposer chat={chat} tone="panel" actions={<button type="button" aria-label="후원하기">$</button>} />)
    expect(html).toMatch(/<input[^>]*aria-label="채팅 메시지"/)
    expect(html).toMatch(/<button[^>]*type="submit"[^>]*aria-label="보내기"/)
    // 글자 "보내기" 버튼은 없다: 텍스트 노드로는 나오지 않는다(aria-label · title만)
    expect(html).not.toMatch(/>\s*보내기\s*</)
    expect(html).not.toContain('enterKeyHint')
    // 순서: actions → 입력창 → 보내기. 보내기는 입력창과 같은 relative 칸 안
    expect(html.indexOf('후원하기')).toBeLessThan(html.indexOf('채팅 메시지'))
    expect(html.indexOf('채팅 메시지')).toBeLessThan(html.indexOf('aria-label="보내기"'))
    expect(html).toMatch(/<div class="relative[^"]*"><input[^>]*>[\s\S]*aria-label="보내기"/)
    expect(html).toContain('pr-10')
    // 한 줄: form이 flex
    expect(html).toMatch(/<form class="flex /)
  })

  it('panel: actions가 없으면 왼쪽 칸을 만들지 않는다', () => {
    const html = renderToString(<ChatComposer chat={chat} tone="panel" />)
    expect(html).not.toContain('shrink-0 items-center gap-1')
  })

  it('서버가 준 채팅 오류 문구를 role="alert"로 보인다', () => {
    const html = renderToString(<ChatComposer chat={{ ...chat, chatError: { code: 'CHAT_RATE_LIMITED', message: '너무 빨리 보내고 있습니다.' } }} tone="overlay" />)
    expect(html).toMatch(/<p role="alert"[^>]*>너무 빨리 보내고 있습니다\.<\/p>/)
  })
})
