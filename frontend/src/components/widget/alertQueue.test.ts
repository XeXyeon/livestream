import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AlertQueue, RecentIds, parseDonation, speechText } from './alertQueue'
import type { WidgetDonation } from './alertQueue'

const donation = (id: number, message: string | null = '응원합니다'): WidgetDonation => ({
  version: 1, type: 'donation', id, broadcastId: 7, donor: { nickname: '시청자' }, amount: 5000, message, createdAt: '2026-11-11T01:00:00Z',
})

describe('parseDonation · speechText', () => {
  it('SSE data를 해석하고, 모양이 틀리면 null', () => {
    expect(parseDonation(JSON.stringify(donation(3)))?.id).toBe(3)
    expect(parseDonation('{"id":"x"}')).toBeNull()
    expect(parseDonation('not json')).toBeNull()
  })

  it('읽을 문장: 메시지가 없으면 콜론 없이', () => {
    expect(speechText(donation(1))).toBe('시청자님이 5000원 후원: 응원합니다')
    expect(speechText(donation(1, '  '))).toBe('시청자님이 5000원 후원')
  })
})

describe('RecentIds', () => {
  it('같은 id는 한 번만 받고, 상한을 넘으면 가장 오래된 것부터 잊는다', () => {
    const ids = new RecentIds(2)
    expect(ids.add(1)).toBe(true)
    expect(ids.add(1)).toBe(false)
    ids.add(2)
    ids.add(3)
    expect(ids.add(1)).toBe(true)
  })
})

describe('AlertQueue', () => {
  beforeEach(() => { vi.useFakeTimers() })
  afterEach(() => { vi.useRealTimers() })

  it('같은 후원 id가 두 번 와도(재연결 경계) 한 번만 보여 주고 읽는다', async () => {
    const shown: (number | null)[] = []
    const speak = vi.fn(() => Promise.resolve())
    const queue = new AlertQueue({ show: (d) => shown.push(d?.id ?? null), speak })

    expect(queue.push(donation(10))).toBe(true)
    expect(queue.push(donation(10))).toBe(false)
    await vi.runAllTimersAsync()

    expect(shown).toEqual([10, null])
    expect(speak).toHaveBeenCalledTimes(1)
  })

  it('순서대로 하나씩: 카드 6초와 읽기가 모두 끝나야 다음으로 넘어간다', async () => {
    const shown: (number | null)[] = []
    let finishSpeech: () => void = () => undefined
    const speak = vi.fn(() => new Promise<void>((resolve) => { finishSpeech = resolve }))
    const queue = new AlertQueue({ show: (d) => shown.push(d?.id ?? null), speak, displayMs: 6000, gapMs: 500 })

    queue.push(donation(1))
    queue.push(donation(2))
    await vi.advanceTimersByTimeAsync(10_000) // 6초가 지나도 읽기가 안 끝났으면 그대로
    expect(shown).toEqual([1])

    finishSpeech()
    await vi.advanceTimersByTimeAsync(500)
    expect(shown).toEqual([1, null, 2])
    expect(speak).toHaveBeenLastCalledWith('시청자님이 5000원 후원: 응원합니다')
  })

  it('dispose 뒤에는 받지도 보여 주지도 않는다', async () => {
    const show = vi.fn()
    const queue = new AlertQueue({ show, speak: () => Promise.resolve() })
    queue.dispose()
    expect(queue.push(donation(1))).toBe(false)
    await vi.runAllTimersAsync()
    expect(show).not.toHaveBeenCalled()
  })

  it('읽기가 예외를 던져도 큐는 멈추지 않고 다음 후원으로 넘어간다', async () => {
    const shown: (number | null)[] = []
    const speak = vi.fn(() => { throw new Error('speak failed') })
    const queue = new AlertQueue({ show: (d) => shown.push(d?.id ?? null), speak })
    queue.push(donation(1))
    await vi.runAllTimersAsync()
    queue.push(donation(2))
    await vi.runAllTimersAsync()
    expect(shown).toEqual([1, null, 2, null])
  })
})
