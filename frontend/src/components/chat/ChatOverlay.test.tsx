import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ChatEntry } from './chatState'
import ChatOverlay from './ChatOverlay'
import type { useBroadcastChat } from './useBroadcastChat'

// 테스트 환경이 node(DOM 없음)라 서버 렌더로 줄 모양 · 문구를 본다. 자동 스크롤은 effect라 여기서 보지 않는다.
type Chat = ReturnType<typeof useBroadcastChat>

const entries: ChatEntry[] = [
  { type: 'chat', id: '1', member: { id: 1, nickname: '시청자', isChannelOwner: false }, content: '안녕하세요', sentAt: '2026-11-17T00:00:00Z' },
  { type: 'chat', id: '2', member: { id: 2, nickname: '방장', isChannelOwner: true }, content: '반갑습니다', sentAt: '2026-11-17T00:00:01Z' },
  { type: 'donation', id: 7, donor: { nickname: '후원자' }, amount: 5000, message: '화이팅', approvedAt: '2026-11-17T00:00:02Z' },
]
const makeChat = (over: Partial<Chat> = {}): Chat => ({
  entries, connection: 'connected', chatError: null, send: () => true, clearError: () => {}, ...over,
})
const render = (chat: Chat, hidden?: boolean) => renderToString(<ChatOverlay chat={chat} hidden={hidden} />)

describe('ChatOverlay', () => {
  it('entries를 줄로 그리고 닉네임 · 내용이 보인다', () => {
    const html = render(makeChat())
    expect(html).toContain('aria-live="polite"')
    expect(html).toContain('시청자')
    expect(html).toContain('안녕하세요')
    expect(html.match(/<li /g)).toHaveLength(3)
  })

  it('방송인 줄에만 "방송인" 배지가 붙는다', () => {
    const html = render(makeChat())
    expect(html.match(/방송인/g)).toHaveLength(1)
    expect(html.indexOf('방송인')).toBeGreaterThan(html.indexOf('시청자'))
    expect(html).toContain('text-sky-300')
  })

  it('후원 줄은 호박색 칸에 금액 · 후원 문구 · 메시지를 보인다', () => {
    const html = render(makeChat())
    const donation = html.match(/<li data-kind="donation"[\s\S]*?<\/li>/)?.[0] ?? ''
    expect(donation).toContain('bg-amber-500/60')
    expect(donation).toContain('후원자')
    expect(donation.replaceAll('<!-- -->', '')).toContain('5,000원')
    expect(donation).toContain('화이팅')
  })

  it('hidden이면 아무것도 그리지 않는다', () => {
    const html = render(makeChat(), true)
    expect(html).toBe('')
  })

  it('연결 중 · 재연결 중이면 목록 위에 문구를 보인다', () => {
    const connecting = render(makeChat({ entries: [], connection: 'connecting' }))
    expect(connecting).toContain('채팅에 연결하는 중…')
    expect(render(makeChat({ connection: 'reconnecting' }))).toContain('채팅 연결이 끊겨 다시 연결하는 중…')
    expect(render(makeChat())).not.toContain('연결')
  })

  it('비어 있고 연결돼 있으면 줄이 없다', () => {
    expect(render(makeChat({ entries: [] }))).not.toContain('<li')
  })
})
