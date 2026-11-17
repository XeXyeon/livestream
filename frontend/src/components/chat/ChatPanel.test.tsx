import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import ChatPanel from './ChatPanel'
import type { useBroadcastChat } from './useBroadcastChat'

type Chat = ReturnType<typeof useBroadcastChat>
const chat: Chat = { entries: [], connection: 'connected', chatError: null, send: () => true, clearError: () => {} }

describe('ChatPanel composer', () => {
  it('기본(inside)은 패널 안에 입력창이 있다', () => {
    const html = renderToString(<ChatPanel chat={chat} />)
    expect(html).toMatch(/<input[^>]*aria-label="채팅 메시지"/)
  })

  it('none이면 입력창을 그리지 않지만 목록은 그대로다', () => {
    const html = renderToString(<ChatPanel chat={chat} composer="none" />)
    expect(html).not.toContain('채팅 메시지')
    expect(html).toContain('아직 채팅이 없습니다.')
  })

  it('방송이 끝나면 none이어도 패널 안에 종료 문구가 남는다', () => {
    const html = renderToString(<ChatPanel chat={chat} composer="none" ended />)
    expect(html).toContain('방송이 종료되어 채팅을 보낼 수 없습니다.')
    expect(html).not.toContain('채팅 메시지')
  })
})
