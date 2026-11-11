// 알림 위젯(S11 · §6.7)의 후원 알림 처리: 이벤트 해석 · 후원 id 중복 제거 · 순차 표시 큐.
// 화면 · 음성과 분리해 vitest로 검증한다.

/** §4.15 `event: donation` data */
export type WidgetDonation = {
  version: number
  type: 'donation'
  id: number
  broadcastId: number | null
  donor: { nickname: string }
  amount: number
  message: string | null
  createdAt: string
}

export function parseDonation(data: string): WidgetDonation | null {
  try {
    const value = JSON.parse(data) as Partial<WidgetDonation>
    if (typeof value.id !== 'number' || typeof value.amount !== 'number' || !value.donor || typeof value.donor.nickname !== 'string') return null
    return value as WidgetDonation
  } catch {
    return null
  }
}

/** §6.7 S11: "{nickname}님이 {amount}원 후원: {message}" (메시지가 없으면 콜론 없이) */
export function speechText(donation: WidgetDonation): string {
  const head = `${donation.donor.nickname}님이 ${donation.amount}원 후원`
  const message = donation.message?.trim()
  return message ? `${head}: ${message}` : head
}

/** 최근에 받은 후원 id. 재연결 · `Last-Event-ID` 경계에서 같은 후원이 두 번 올 수 있다(§4.15). */
export class RecentIds {
  private readonly ids = new Set<number>()
  private readonly limit: number

  constructor(limit = 500) {
    this.limit = limit
  }

  /** 처음 본 id면 기억하고 true, 이미 본 id면 false */
  add(id: number): boolean {
    if (this.ids.has(id)) return false
    this.ids.add(id)
    if (this.ids.size > this.limit) {
      const oldest = this.ids.values().next().value
      if (oldest !== undefined) this.ids.delete(oldest)
    }
    return true
  }
}

export const ALERT_DISPLAY_MS = 6000
export const ALERT_GAP_MS = 600

type AlertQueueOptions = {
  /** 지금 보여 줄 후원(없으면 null) */
  show: (donation: WidgetDonation | null) => void
  /** 읽기가 끝나면 resolve. 실패해도 resolve한다. */
  speak: (text: string) => Promise<void>
  displayMs?: number
  gapMs?: number
}

/**
 * 후원 알림을 하나씩 보여 준다. 한 알림은 카드 표시(6초)와 음성 읽기가 둘 다 끝나야 내려가고,
 * 잠깐 쉰 뒤 다음 알림으로 넘어간다.
 */
export class AlertQueue {
  private readonly seen = new RecentIds()
  private readonly pending: WidgetDonation[] = []
  private running = false
  private disposed = false
  private readonly options: Required<AlertQueueOptions>

  constructor(options: AlertQueueOptions) {
    this.options = { displayMs: ALERT_DISPLAY_MS, gapMs: ALERT_GAP_MS, ...options }
  }

  /** 새 후원이면 큐에 넣고 true, 이미 받은 후원이면 false */
  push(donation: WidgetDonation): boolean {
    if (this.disposed || !this.seen.add(donation.id)) return false
    this.pending.push(donation)
    if (!this.running) void this.run()
    return true
  }

  dispose(): void {
    this.disposed = true
    this.pending.length = 0
  }

  private async run(): Promise<void> {
    this.running = true
    const { show, speak, displayMs, gapMs } = this.options
    while (!this.disposed && this.pending.length > 0) {
      const donation = this.pending.shift()!
      show(donation)
      await Promise.all([delay(displayMs), speak(speechText(donation))])
      if (this.disposed) break
      show(null)
      await delay(gapMs)
    }
    this.running = false
  }
}

const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))
