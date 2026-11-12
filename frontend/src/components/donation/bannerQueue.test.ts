import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BANNER_DISPLAY_MS, BANNER_GAP_MS, BannerQueue } from './bannerQueue'

type Item = { id: number }

describe('BannerQueue', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('하나씩 5초 보여 주고, 잠깐 쉰 뒤 다음 것을 보여 준다', () => {
    const shown: (number | null)[] = []
    const queue = new BannerQueue<Item>((item) => shown.push(item?.id ?? null))
    queue.push({ id: 1 })
    queue.push({ id: 2 })
    expect(shown).toEqual([1])

    vi.advanceTimersByTime(BANNER_DISPLAY_MS)
    expect(shown).toEqual([1, null])
    vi.advanceTimersByTime(BANNER_GAP_MS)
    expect(shown).toEqual([1, null, 2])
    vi.advanceTimersByTime(BANNER_DISPLAY_MS + BANNER_GAP_MS)
    expect(shown).toEqual([1, null, 2, null])
  })

  it('같은 id는 한 번만 보여 준다', () => {
    const shown: (number | null)[] = []
    const queue = new BannerQueue<Item>((item) => shown.push(item?.id ?? null))
    queue.push({ id: 1 })
    queue.push({ id: 1 })
    vi.advanceTimersByTime((BANNER_DISPLAY_MS + BANNER_GAP_MS) * 2)
    expect(shown).toEqual([1, null])
  })

  it('비었다가 새로 들어오면 바로 보여 준다', () => {
    const shown: (number | null)[] = []
    const queue = new BannerQueue<Item>((item) => shown.push(item?.id ?? null))
    queue.push({ id: 1 })
    vi.advanceTimersByTime(BANNER_DISPLAY_MS + BANNER_GAP_MS)
    queue.push({ id: 2 })
    expect(shown).toEqual([1, null, 2])
  })

  it('dispose 뒤에는 아무것도 보이지 않는다', () => {
    const shown: (number | null)[] = []
    const queue = new BannerQueue<Item>((item) => shown.push(item?.id ?? null))
    queue.push({ id: 1 })
    queue.push({ id: 2 })
    queue.dispose()
    queue.push({ id: 3 })
    vi.advanceTimersByTime((BANNER_DISPLAY_MS + BANNER_GAP_MS) * 3)
    expect(shown).toEqual([1])
  })
})
