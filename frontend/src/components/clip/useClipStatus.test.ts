import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { QueryClient as QueryClientType } from '@tanstack/react-query'
import type { ClipDetail, ClipStatus } from '../../api/types'

const getClip = vi.fn<(id: number) => Promise<ClipDetail>>()
vi.mock('../../api/clips', () => ({ getClip: (id: number) => getClip(id) }))

// 테스트 환경이 node라 React Query가 "서버"로 보고 refetchInterval을 끈다(query-core isServer = window 없음).
// 브라우저처럼 보이게 window를 둔 뒤 React Query를 불러온다.
vi.stubGlobal('window', globalThis)
const { QueryClient, QueryObserver } = await import('@tanstack/react-query')
const { clipQueryOptions, CLIP_POLL_MS } = await import('./useClipStatus')

const clip = (status: ClipStatus) => ({ id: 7, status }) as ClipDetail

describe('useClipStatus(clipQueryOptions)', () => {
  let client: QueryClientType
  beforeEach(() => {
    vi.useFakeTimers()
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    getClip.mockReset()
  })
  afterEach(() => {
    client.clear()
    vi.useRealTimers()
  })

  it('PROCESSING이면 2초마다 다시 부르고 READY가 되면 멈춘다', async () => {
    getClip.mockResolvedValueOnce(clip('PROCESSING')).mockResolvedValueOnce(clip('PROCESSING')).mockResolvedValue(clip('READY'))
    const observer = new QueryObserver(client, clipQueryOptions(7))
    const unsubscribe = observer.subscribe(() => {})

    await vi.advanceTimersByTimeAsync(0)
    expect(getClip).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(CLIP_POLL_MS)
    expect(getClip).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(CLIP_POLL_MS)
    expect(getClip).toHaveBeenCalledTimes(3)
    expect(observer.getCurrentResult().data?.status).toBe('READY')

    await vi.advanceTimersByTimeAsync(CLIP_POLL_MS * 5)
    expect(getClip).toHaveBeenCalledTimes(3)
    unsubscribe()
  })

  it('처음부터 FAILED면 다시 부르지 않는다', async () => {
    getClip.mockResolvedValue(clip('FAILED'))
    const observer = new QueryObserver(client, clipQueryOptions(7))
    const unsubscribe = observer.subscribe(() => {})
    await vi.advanceTimersByTimeAsync(CLIP_POLL_MS * 3)
    expect(getClip).toHaveBeenCalledTimes(1)
    unsubscribe()
  })
})
