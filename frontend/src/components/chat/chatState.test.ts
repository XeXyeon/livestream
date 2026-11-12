import { describe, expect, it } from 'vitest'
import type { ChatMessage, DonationMessage } from '../../api/types'
import { CHAT_LIMIT, applyChatMessage, chatInputError } from './chatState'

const chat = (id: string, sentAt = '2026-11-12T00:00:00Z'): ChatMessage => ({
  type: 'chat', id, member: { id: 1, nickname: 'a', isChannelOwner: false }, content: id, sentAt,
})
const donation = (id: number): DonationMessage => ({
  type: 'donation', id, donor: { nickname: 'b' }, amount: 1000, message: null, approvedAt: '2026-11-12T00:00:00Z',
})

describe('applyChatMessage', () => {
  it('history로 목록을 채우고 이후 chat · donation을 뒤에 붙인다', () => {
    let entries = applyChatMessage([], { type: 'history', messages: [chat('1'), donation(7)] })
    entries = applyChatMessage(entries, chat('2'))
    entries = applyChatMessage(entries, donation(8))
    expect(entries.map((e) => `${e.type}:${e.id}`)).toEqual(['chat:1', 'donation:7', 'chat:2', 'donation:8'])
  })

  it('재연결 뒤 다시 온 history는 없는 줄만 붙이고, 같은 메시지가 두 번 와도 한 번만 둔다', () => {
    let entries = applyChatMessage([], { type: 'history', messages: [chat('1'), chat('2')] })
    entries = applyChatMessage(entries, chat('2'))
    entries = applyChatMessage(entries, { type: 'history', messages: [chat('2'), chat('3'), donation(7)] })
    expect(entries.map((e) => e.id)).toEqual(['1', '2', '3', 7])
  })

  it('history보다 먼저 온 실시간 메시지는 history를 합친 뒤 시각 순으로 뒤에 놓인다', () => {
    let entries = applyChatMessage([], chat('live', '2026-11-12T00:00:05.123456Z'))
    entries = applyChatMessage(entries, { type: 'history', messages: [chat('old1', '2026-11-12T00:00:01Z'), chat('old2', '2026-11-12T00:00:02.5Z')] })
    expect(entries.map((e) => e.id)).toEqual(['old1', 'old2', 'live'])
  })

  it('chat id와 donation id가 같은 값이어도 다른 줄이다', () => {
    const entries = applyChatMessage([chat('7')], donation(7))
    expect(entries).toHaveLength(2)
  })

  it('status · viewers는 목록을 바꾸지 않는다(같은 배열)', () => {
    const entries = [chat('1')]
    expect(applyChatMessage(entries, { type: 'viewers', count: 3 })).toBe(entries)
    expect(applyChatMessage(entries, { type: 'status', status: 'DISCONNECTED', at: 'x' })).toBe(entries)
  })

  it(`${CHAT_LIMIT}줄을 넘으면 오래된 것부터 버린다`, () => {
    let entries = applyChatMessage([], { type: 'history', messages: Array.from({ length: CHAT_LIMIT }, (_, i) => chat(String(i))) })
    entries = applyChatMessage(entries, chat('new'))
    expect(entries).toHaveLength(CHAT_LIMIT)
    expect(entries[0].id).toBe('1')
    expect(entries.at(-1)?.id).toBe('new')
  })
})

describe('chatInputError', () => {
  it('공백만 있으면 막고, 앞뒤 공백을 뺀 200자(코드 포인트)까지 허용한다', () => {
    expect(chatInputError('   ')).not.toBeNull()
    expect(chatInputError(` ${'가'.repeat(200)} `)).toBeNull()
    expect(chatInputError('가'.repeat(201))).not.toBeNull()
    expect(chatInputError('😀'.repeat(200))).toBeNull() // 서버는 코드 포인트로 센다
  })
})
