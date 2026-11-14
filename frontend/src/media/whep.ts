import { resolveLocation, waitForIceGathering, discardSession } from './webrtc'

// §6.5 WhepPlayer: MediaMTX WHEP로 저지연 재생. 헤더 없이 POST(시청은 인증 콜백이 허용).
// 끊기거나 아직 송출이 붙지 않았으면(404) 'waiting'으로 바꾸고 3초마다 다시 붙는다. close() 전까지 계속한다.
export type WhepState = 'connecting' | 'playing' | 'waiting' | 'closed'

export const WHEP_RETRY_MS = 3000

export class WhepPlayer {
  private pc: RTCPeerConnection | null = null
  private video: HTMLVideoElement | null = null
  private url = ''
  private sessionUrl: string | null = null
  private closed = false
  private generation = 0
  private attempting = false
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private state: WhepState = 'connecting'
  private readonly listeners = new Set<(state: WhepState) => void>()

  attach(video: HTMLVideoElement, url: string): void {
    this.video = video
    this.url = url
    void this.connect()
  }

  onState(listener: (state: WhepState) => void): () => void {
    this.listeners.add(listener)
    listener(this.state)
    return () => { this.listeners.delete(listener) }
  }

  getState(): WhepState {
    return this.state
  }

  /** 방송이 다시 LIVE가 됐을 때 기다리지 않고 바로 다시 붙는다. 재생 중이면 아무것도 하지 않는다. */
  reconnect(): void {
    // 연결 시도(POST)가 진행 중이면 그 결과를 기다린다. 버리고 새로 붙으면 방금 만든 세션이 버려진다.
    if (this.closed || this.state === 'playing' || this.attempting) return
    void this.connect()
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    this.generation++
    this.clearRetry()
    this.teardown()
    if (this.video) this.video.srcObject = null
    this.setState('closed')
  }

  private async connect(): Promise<void> {
    this.clearRetry()
    this.teardown()
    const generation = ++this.generation
    const stale = () => this.closed || generation !== this.generation
    // 재시도 중에는 'waiting'을 유지해 화면이 연결 중 ↔ 대기 중으로 깜빡이지 않게 한다.
    if (this.state !== 'waiting') this.setState('connecting')

    const pc = new RTCPeerConnection({ iceServers: [] })
    this.pc = pc
    pc.addTransceiver('video', { direction: 'recvonly' })
    pc.addTransceiver('audio', { direction: 'recvonly' })
    const stream = new MediaStream()
    pc.addEventListener('track', (event) => {
      stream.addTrack(event.track)
      if (this.video && this.video.srcObject !== stream) this.video.srcObject = stream
    })
    pc.addEventListener('connectionstatechange', () => {
      if (stale()) return
      if (pc.connectionState === 'connected') this.setState('playing')
      else if (pc.connectionState === 'failed' || pc.connectionState === 'closed') this.waitAndRetry()
      else if (pc.connectionState === 'disconnected') {
        // 잠깐 끊겼다 스스로 돌아오는 경우가 있어 한 주기 기다려 보고 그래도 아니면 다시 붙는다.
        this.setState('waiting')
        this.scheduleRetry()
      }
    })

    this.attempting = true
    try {
      await pc.setLocalDescription(await pc.createOffer())
      await waitForIceGathering(pc)
      if (stale()) return
      const response = await fetch(this.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sdp' },
        body: pc.localDescription?.sdp ?? '',
      })
      if (stale()) {
        if (response.ok) discardSession(response, this.url)
        return
      }
      if (!response.ok) {
        this.waitAndRetry()
        return
      }
      this.sessionUrl = resolveLocation(response.headers.get('Location'), this.url)
      await pc.setRemoteDescription({ type: 'answer', sdp: await response.text() })
    } catch {
      if (!stale()) this.waitAndRetry()
    } finally {
      if (!stale()) this.attempting = false
    }
  }

  private waitAndRetry(): void {
    this.setState('waiting')
    this.scheduleRetry()
  }

  private scheduleRetry(): void {
    if (this.closed) return
    this.clearRetry()
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      if (!this.closed && this.pc?.connectionState !== 'connected') void this.connect()
    }, WHEP_RETRY_MS)
  }

  private clearRetry(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer)
    this.retryTimer = null
  }

  private teardown(): void {
    const sessionUrl = this.sessionUrl
    this.sessionUrl = null
    if (sessionUrl) void fetch(sessionUrl, { method: 'DELETE' }).catch(() => undefined)
    this.pc?.close()
    this.pc = null
  }

  private setState(state: WhepState): void {
    if (this.state === state) return
    this.state = state
    for (const listener of this.listeners) listener(state)
  }
}
