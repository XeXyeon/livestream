// 알림 위젯 SSE 구독(§4.15 `GET /api/public/widget/{widgetKey}/events`).
// 끊기면 브라우저 EventSource의 자동 재연결에 맡기고 `Last-Event-ID`도 브라우저가 보내게 둔다(§9 FE-9 ③).
// 단, 브라우저는 200이 아닌 응답(옛 키 404 · 서버 오류 502 등)에는 재연결하지 않고 연결을 닫는다.
// 그때만 상태를 한 번 물어 404면 "키가 바뀜"으로 멈추고, 아니면 잠시 뒤 새 연결을 연다.
import { parseDonation } from './alertQueue'
import type { WidgetDonation } from './alertQueue'

export type WidgetStatus = 'connecting' | 'connected' | 'reconnecting' | 'invalid-key'

export const WIDGET_REOPEN_MS = 5000

type EventSourceLike = Pick<EventSource, 'addEventListener' | 'close' | 'readyState'> & {
  onerror: ((event: Event) => void) | null
}

type Options = {
  onDonation: (donation: WidgetDonation) => void
  onStatus: (status: WidgetStatus) => void
  createEventSource?: (url: string) => EventSourceLike
  fetchStatus?: (url: string) => Promise<number | null>
}

export function widgetEventsUrl(widgetKey: string): string {
  return `/api/public/widget/${encodeURIComponent(widgetKey)}/events`
}

/** 닫힌 연결의 이유를 알아낸다. 200이면 스트림이 열리므로 헤더만 보고 끊는다. 네트워크 오류는 null. */
async function defaultFetchStatus(url: string): Promise<number | null> {
  const controller = new AbortController()
  try {
    const response = await fetch(url, { headers: { Accept: 'text/event-stream' }, signal: controller.signal })
    return response.status
  } catch {
    return null
  } finally {
    controller.abort()
  }
}

const CLOSED = 2 // EventSource.CLOSED (테스트 환경에 EventSource가 없어 숫자로 둔다)

export function subscribeWidgetEvents(widgetKey: string, options: Options): () => void {
  const url = widgetEventsUrl(widgetKey)
  const createEventSource = options.createEventSource ?? ((target: string) => new EventSource(target))
  const fetchStatus = options.fetchStatus ?? defaultFetchStatus
  let source: EventSourceLike | null = null
  let reopenTimer: ReturnType<typeof setTimeout> | undefined
  let stopped = false

  const open = () => {
    if (stopped) return
    const current = createEventSource(url)
    source = current
    current.addEventListener('ready', () => {
      if (source === current) options.onStatus('connected')
    })
    current.addEventListener('donation', (event) => {
      if (source !== current) return
      const donation = parseDonation((event as MessageEvent<string>).data)
      if (donation) options.onDonation(donation)
    })
    current.onerror = () => {
      if (source !== current || stopped) return
      if (current.readyState !== CLOSED) {
        options.onStatus('reconnecting') // 브라우저가 Last-Event-ID를 붙여 스스로 다시 붙는다
        return
      }
      current.close()
      void fetchStatus(url).then((status) => {
        if (stopped || source !== current) return
        if (status === 404) {
          options.onStatus('invalid-key')
          return
        }
        options.onStatus('reconnecting')
        reopenTimer = setTimeout(open, WIDGET_REOPEN_MS)
      })
    }
  }

  options.onStatus('connecting')
  open()
  return () => {
    stopped = true
    clearTimeout(reopenTimer)
    source?.close()
    source = null
  }
}
