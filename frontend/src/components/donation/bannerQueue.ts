// §6.7 S2 DonationBanner(플레이어 위 5초) · S3 후원 알림: 후원을 하나씩 차례로 보여 준다.
// 같은 후원이 두 번 와도(재연결 뒤 history 등) 한 번만 보인다.

export const BANNER_DISPLAY_MS = 5000
export const BANNER_GAP_MS = 400
const SEEN_LIMIT = 200

export class BannerQueue<T extends { id: number }> {
  private readonly seen = new Set<number>()
  private readonly pending: T[] = []
  private timer: ReturnType<typeof setTimeout> | undefined
  private running = false
  private disposed = false
  private readonly show: (item: T | null) => void
  private readonly displayMs: number
  private readonly gapMs: number

  constructor(show: (item: T | null) => void, displayMs = BANNER_DISPLAY_MS, gapMs = BANNER_GAP_MS) {
    this.show = show
    this.displayMs = displayMs
    this.gapMs = gapMs
  }

  push(item: T): void {
    if (this.disposed || this.seen.has(item.id)) return
    this.seen.add(item.id)
    if (this.seen.size > SEEN_LIMIT) {
      const oldest = this.seen.values().next().value
      if (oldest !== undefined) this.seen.delete(oldest)
    }
    this.pending.push(item)
    if (!this.running) this.next()
  }

  dispose(): void {
    this.disposed = true
    this.pending.length = 0
    clearTimeout(this.timer)
  }

  private next(): void {
    const item = this.pending.shift()
    if (!item || this.disposed) {
      this.running = false
      return
    }
    this.running = true
    this.show(item)
    this.timer = setTimeout(() => {
      this.show(null)
      this.timer = setTimeout(() => this.next(), this.gapMs)
    }, this.displayMs)
  }
}
