import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { QueryClient as QueryClientType } from '@tanstack/react-query'
import type { ClipDraftDetail, ClipDraftStatus } from '../../api/types'

const getDraft = vi.fn<(id: number) => Promise<ClipDraftDetail>>()
vi.mock('../../api/clips', () => ({ getDraft: (id: number) => getDraft(id) }))

// node 환경에서는 React Query가 refetchInterval을 끄므로 window를 둔 뒤 불러온다(useClipStatus.test.ts와 같은 방식).
vi.stubGlobal('window', globalThis)
const { QueryClient, QueryObserver } = await import('@tanstack/react-query')
const { clipDraftQueryOptions, DRAFT_POLL_MS } = await import('./useClipDraft')

const draft = (status: ClipDraftStatus) => ({ id: 3, status }) as ClipDraftDetail

describe('useClipDraft(clipDraftQueryOptions)', () => {
  let client: QueryClientType
  beforeEach(() => {
    vi.useFakeTimers()
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    getDraft.mockReset()
  })
  afterEach(() => {
    client.clear()
    vi.useRealTimers()
  })

  it('PENDING이면 2초마다 다시 부르고 READY가 되면 멈춘다', async () => {
    getDraft.mockResolvedValueOnce(draft('PENDING')).mockResolvedValueOnce(draft('PENDING')).mockResolvedValue(draft('READY'))
    const observer = new QueryObserver(client, clipDraftQueryOptions(3))
    const unsubscribe = observer.subscribe(() => {})

    await vi.advanceTimersByTimeAsync(0)
    expect(getDraft).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(DRAFT_POLL_MS)
    expect(getDraft).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(DRAFT_POLL_MS)
    expect(getDraft).toHaveBeenCalledTimes(3)
    expect(observer.getCurrentResult().data?.status).toBe('READY')

    // READY 뒤에는 다시 부르지 않는다(부를 때마다 토큰이 바뀌어 영상이 다시 로드되므로)
    await vi.advanceTimersByTimeAsync(DRAFT_POLL_MS * 5)
    expect(getDraft).toHaveBeenCalledTimes(3)
    unsubscribe()
  })

  it('FAILED면 다시 부르지 않는다', async () => {
    getDraft.mockResolvedValue(draft('FAILED'))
    const observer = new QueryObserver(client, clipDraftQueryOptions(3))
    const unsubscribe = observer.subscribe(() => {})
    await vi.advanceTimersByTimeAsync(DRAFT_POLL_MS * 3)
    expect(getDraft).toHaveBeenCalledTimes(1)
    unsubscribe()
  })
})
