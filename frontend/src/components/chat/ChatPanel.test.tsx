import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import ChatPanel from './ChatPanel'
import type { useBroadcastChat } from './useBroadcastChat'

type Chat = ReturnType<typeof useBroadcastChat>
const chat: Chat = { entries: [], connection: 'connected', chatError: null, send: () => true, clearError: () => {} }

// 입력칸 칸의 class를 꺼낸다(입력창을 품은 가장 가까운 div)
function composerCell(html: string): string {
  return html.match(/<div class="([^"]*sticky bottom-0[^"]*)">/)?.[1] ?? ''
}

describe('ChatPanel 입력칸 칸', () => {
  it('입력창이 패널 안 입력칸 칸에 있고, lg 미만은 sticky bottom-0 · lg 이상은 흐름(lg:static)', () => {
    const html = renderToString(<ChatPanel chat={chat} />)
    expect(html).toMatch(/<input[^>]*aria-label="채팅 메시지"/)
    const cell = composerCell(html)
    expect(cell).toContain('sticky bottom-0')
    expect(cell).toContain('lg:static')
    expect(cell).toContain('bg-surface')
    // 입력칸 칸은 목록 뒤, 패널의 마지막 칸
    expect(html.indexOf('아직 채팅이 없습니다.')).toBeLessThan(html.indexOf('sticky bottom-0'))
    expect(html.indexOf('sticky bottom-0')).toBeLessThan(html.indexOf('채팅 메시지'))
  })

  it('카드에는 overflow를 주지 않는다(sticky가 뷰포트에 붙으려면) — 스크롤은 목록만', () => {
    const html = renderToString(<ChatPanel chat={chat} className="h-[28rem]" />)
    expect(html.match(/<aside[^>]*class="([^"]*)"/)?.[1]).not.toContain('overflow')
  })

  it('방송이 끝나면 같은 칸에 종료 문구가 들어가고 입력창은 없다', () => {
    const html = renderToString(<ChatPanel chat={chat} ended />)
    expect(composerCell(html)).toContain('sticky bottom-0')
    expect(html).toContain('방송이 종료되어 채팅을 보낼 수 없습니다.')
    expect(html).not.toContain('채팅 메시지')
  })

  it('framed=false여도 lg 미만에서는 위 구분선이 있다', () => {
    const cell = composerCell(renderToString(<ChatPanel chat={chat} framed={false} />))
    expect(cell).toContain('max-lg:border-t')
  })
})
