import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WIDGET_REOPEN_MS, subscribeWidgetEvents, widgetEventsUrl } from './widgetEvents'
import type { WidgetStatus } from './widgetEvents'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  readyState = 0
  onerror: ((event: Event) => void) | null = null
  closed = false
  private readonly listeners = new Map<string, ((event: Event) => void)[]>()
  readonly url: string

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(type: string, listener: (event: Event) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  close() {
    this.closed = true
    this.readyState = 2
  }

  emit(type: string, data?: string) {
    this.listeners.get(type)?.forEach((listener) => listener({ data } as MessageEvent))
  }

  fail(readyState: number) {
    this.readyState = readyState
    this.onerror?.(new Event('error'))
  }
}

describe('subscribeWidgetEvents', () => {
  let statuses: WidgetStatus[]
  let donations: number[]

  beforeEach(() => {
    vi.useFakeTimers()
    FakeEventSource.instances = []
    statuses = []
    donations = []
  })
  afterEach(() => { vi.useRealTimers() })

  const subscribe = (fetchStatus: (url: string) => Promise<number | null>) => subscribeWidgetEvents('k/ey', {
    onDonation: (d) => donations.push(d.id),
    onStatus: (s) => statuses.push(s),
    createEventSource: (url) => new FakeEventSource(url) as unknown as EventSource,
    fetchStatus,
  })

  it('위젯 키를 인코딩한 주소로 구독하고, ready · donation을 전달한다', () => {
    subscribe(() => Promise.resolve(200))
    const source = FakeEventSource.instances[0]
    expect(source.url).toBe(widgetEventsUrl('k/ey'))
    expect(source.url).toBe('/api/public/widget/k%2Fey/events')

    source.emit('ready', '{"channelId":3,"version":1}')
    source.emit('donation', JSON.stringify({ version: 1, type: 'donation', id: 9, broadcastId: 1, donor: { nickname: 'a' }, amount: 1000, message: null, createdAt: 'x' }))
    source.emit('donation', 'broken')
    expect(statuses).toEqual(['connecting', 'connected'])
    expect(donations).toEqual([9])
  })

  it('브라우저가 스스로 재연결하는 끊김(CONNECTING)은 새 연결을 만들지 않는다(Last-Event-ID는 브라우저 몫)', () => {
    const fetchStatus = vi.fn(() => Promise.resolve(200))
    subscribe(fetchStatus)
    FakeEventSource.instances[0].fail(0)
    expect(statuses.at(-1)).toBe('reconnecting')
    expect(fetchStatus).not.toHaveBeenCalled()
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('닫힌 연결이 404(옛 키)면 "키가 바뀜"으로 멈추고 다시 열지 않는다', async () => {
    subscribe(() => Promise.resolve(404))
    FakeEventSource.instances[0].fail(2)
    await vi.advanceTimersByTimeAsync(WIDGET_REOPEN_MS * 2)
    expect(statuses.at(-1)).toBe('invalid-key')
    expect(FakeEventSource.instances).toHaveLength(1)
  })

  it('닫힌 연결이 서버 오류(502)면 잠시 뒤 새 연결을 연다', async () => {
    subscribe(() => Promise.resolve(502))
    FakeEventSource.instances[0].fail(2)
    await vi.advanceTimersByTimeAsync(0)
    expect(statuses.at(-1)).toBe('reconnecting')
    await vi.advanceTimersByTimeAsync(WIDGET_REOPEN_MS)
    expect(FakeEventSource.instances).toHaveLength(2)
  })

  it('구독을 끊으면 연결을 닫고 예약된 재연결도 취소한다', async () => {
    const unsubscribe = subscribe(() => Promise.resolve(502))
    FakeEventSource.instances[0].fail(2)
    await vi.advanceTimersByTimeAsync(0)
    unsubscribe()
    await vi.advanceTimersByTimeAsync(WIDGET_REOPEN_MS)
    expect(FakeEventSource.instances).toHaveLength(1)
  })
})
