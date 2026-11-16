import { describe, expect, it } from 'vitest'
import type { BroadcastSummary, FollowedChannel, RecordingSummary } from '../../api/types'
import { buildSidebarChannels, followsToSidebar, hasSidebar, keepOrder, readCollapsed, writeCollapsed } from './sidebarChannels'

const category = { code: 'GAME', label: '게임' } as const
const live = (id: number, channelId: number, viewerCount: number): BroadcastSummary => ({
  id, title: 't', category, thumbnailUrl: null, status: 'LIVE', startedAt: '2026-11-12T00:00:00Z', viewerCount, channel: { id: channelId, name: `ch${channelId}` },
})
const recording = (id: number, channelId: number): RecordingSummary => ({
  id, broadcastId: id, title: 'r', category, thumbnailUrl: null, durationSeconds: 10, startedAt: `2026-11-1${id % 10}T00:00:00Z`, channel: { id: channelId, name: `ch${channelId}` }, commentCount: 0,
})

describe('buildSidebarChannels', () => {
  it('라이브는 채널별 하나씩 온 순서대로, 시청자 수와 시청 링크', () => {
    const result = buildSidebarChannels([live(1, 10, 50), live(2, 11, 9), live(3, 10, 1)], [])
    expect(result.live.map((row) => [row.channelId, row.viewerCount, row.href])).toEqual([[10, 50, '/live/10'], [11, 9, '/live/11']])
  })
  it('추천은 라이브가 아닌 채널을 중복 없이 최대 개수까지, 채널 링크', () => {
    const result = buildSidebarChannels([live(1, 10, 5)], [recording(1, 10), recording(2, 20), recording(3, 20), recording(4, 30), recording(5, 40)], 2)
    expect(result.recommended.map((row) => [row.channelId, row.href, row.viewerCount])).toEqual([[20, '/channel/20', null], [30, '/channel/30', null]])
    expect(result.recommended[0].lastBroadcastAt).toBe('2026-11-12T00:00:00Z')
  })
})

describe('hasSidebar', () => {
  it('스튜디오 · 로그인 · 가입에는 없다', () => {
    expect(hasSidebar('/')).toBe(true)
    expect(hasSidebar('/live/3')).toBe(true)
    expect(hasSidebar('/me')).toBe(true)
    expect(hasSidebar('/studio')).toBe(false)
    expect(hasSidebar('/studio/pc')).toBe(false)
    expect(hasSidebar('/studios')).toBe(true)
    expect(hasSidebar('/login')).toBe(false)
    expect(hasSidebar('/signup')).toBe(false)
  })
})

describe('접힘 상태 저장', () => {
  it('저장한 값을 읽고, 저장소가 없거나 던지면 펼친 상태', () => {
    const map = new Map<string, string>()
    const storage = { getItem: (key: string) => map.get(key) ?? null, setItem: (key: string, value: string) => { map.set(key, value) } }
    expect(readCollapsed(storage)).toBe(false)
    writeCollapsed(true, storage)
    expect(readCollapsed(storage)).toBe(true)
    const broken = { getItem: () => { throw new Error('denied') }, setItem: () => { throw new Error('denied') } }
    expect(readCollapsed(broken)).toBe(false)
    expect(() => writeCollapsed(true, broken)).not.toThrow()
    expect(readCollapsed(null)).toBe(false)
    // 기본 저장소 접근 자체가 던져도(쿠키 차단) 셸이 깨지지 않는다
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'localStorage', { configurable: true, get() { throw new DOMException('denied', 'SecurityError') } })
    try {
      expect(readCollapsed()).toBe(false)
      expect(() => writeCollapsed(true)).not.toThrow()
    } finally {
      if (descriptor) Object.defineProperty(globalThis, 'localStorage', descriptor)
      else delete (globalThis as { localStorage?: unknown }).localStorage
    }
  })

  it('팔로우 목록은 서버 순서 그대로, 라이브만 시청자 수 · 모두 시청 화면으로', () => {
    const follow = (channelId: number, isLive: boolean, viewerCount: number): FollowedChannel => ({
      channelId, name: `ch${channelId}`, category, isLive, liveBroadcastId: isLive ? channelId * 10 : null, viewerCount, followedAt: '2026-11-12T00:00:00Z',
    })
    const result = followsToSidebar([follow(3, true, 7), follow(14, false, 0)])
    expect(result.map((item) => [item.channelId, item.viewerCount, item.href])).toEqual([[3, 7, '/live/3'], [14, null, '/live/14']])
  })
})

describe('keepOrder', () => {
  const row = (channelId: number, viewerCount: number | null) =>
    ({ channelId, name: `ch${channelId}`, category, viewerCount, lastBroadcastAt: null, href: '' })
  const ids = (rows: { channelId: number }[]) => rows.map((r) => r.channelId)

  it('처음에는 서버 순서 그대로', () => {
    expect(ids(keepOrder([], [row(1, 5), row(2, 3), row(3, null)]))).toEqual([1, 2, 3])
  })
  it('시청자 수가 바뀌어 서버 순서가 달라져도 이전 자리를 지킨다', () => {
    expect(ids(keepOrder([1, 2, 3], [row(3, 9), row(1, 5), row(2, 3)]))).toEqual([1, 2, 3])
  })
  it('새 채널은 같은 쪽 끝에 붙고, 빠진 채널은 사라진다', () => {
    expect(ids(keepOrder([1, 2, 3], [row(4, 9), row(3, 1), row(1, 2)]))).toEqual([1, 3, 4])
  })
  it('라이브가 된 채널은 라이브 아닌 채널보다 위로, 끝난 채널은 아래로', () => {
    expect(ids(keepOrder([1, 2, 3], [row(1, null), row(2, 1), row(3, 4)]))).toEqual([2, 3, 1])
  })
})
