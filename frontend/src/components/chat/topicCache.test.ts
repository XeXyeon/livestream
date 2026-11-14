import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'
import type { BroadcastDetail } from '../../api/types'
import { applyTopicToCache, broadcastKey } from './topicCache'

const detail = {
  id: 7, title: '옛 제목', category: { code: 'GAME', label: '게임' }, status: 'LIVE', viewerCount: 3, followerCount: 1,
} as unknown as BroadcastDetail

describe('applyTopicToCache', () => {
  it('info를 받으면 상세 캐시의 제목 · 카테고리만 고치고 다시 받지 않는다', () => {
    const client = new QueryClient()
    client.setQueryData(broadcastKey(7), detail)
    const handled = applyTopicToCache(client, 7, { type: 'info', title: '새 제목', category: { code: 'TALK', label: '토크' }, at: '2026-11-12T03:00:00Z' })
    expect(handled).toBe(true)
    expect(client.getQueryData(broadcastKey(7))).toEqual({ ...detail, title: '새 제목', category: { code: 'TALK', label: '토크' } })
    expect(client.getQueryState(broadcastKey(7))?.isInvalidated).toBe(false)
  })

  it('캐시가 없으면 만들지 않고, 채팅 메시지는 다루지 않는다', () => {
    const client = new QueryClient()
    expect(applyTopicToCache(client, 7, { type: 'info', title: 't', category: { code: 'GAME', label: '게임' }, at: '2026-11-12T03:00:00Z' })).toBe(true)
    expect(client.getQueryData(broadcastKey(7))).toBeUndefined()
    expect(applyTopicToCache(client, 7, { type: 'chat', id: 'a', member: { id: 1, nickname: 'n', isChannelOwner: false }, content: 'hi', sentAt: '2026-11-12T03:00:00Z' })).toBe(false)
  })
})
