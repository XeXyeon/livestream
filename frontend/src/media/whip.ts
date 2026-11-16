/**
 * WHIP 송출 클라이언트 (PC · 모바일 공용). `media/mixer.ts`가 만든 화면 · 카메라 · 마이크 MediaStream을
 * `RTCPeerConnection`(sendonly 트랙)으로 SDP offer를 만들어 WHIP(HTTP POST)로 MediaMTX에 실시간 송출한다.
 * 끊기면 자동 재연결하고, 종료는 `close()`로 한다.
 */
import { resolveLocation, waitForIceGathering, discardSession } from './webrtc'

// §6.5 WhipClient: 브라우저 송출. Authorization: Bearer <streamKey>로 MediaMTX에 publish한다.
// 연결이 끊기면 3초 간격으로 최대 20번(서버 유예 60초 안) 다시 publish한다.
// 재시도 중 401을 받으면(종료 뒤 30초 차단 · 키 재발급) 바로 멈추고 'ended'로 알린다.
export type WhipState = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'ended' | 'failed' | 'closed'

export const WHIP_RETRY_MS = 3000
export const WHIP_MAX_RETRIES = 20
const VIDEO_MAX_BITRATE = 3_000_000

/**
 * §6.5 비디오 코덱은 H.264 우선(FE-2#1). MediaMTX 녹화기(fMP4)는 VP8을 기록하지 못해 VP8로 협상되면
 * 다시보기 · 클립에 소리만 남는다. H.264를 앞으로 옮기고 나머지(VP8 · VP9 · AV1 · rtx · red · ulpfec)는 순서를 그대로 둔다.
 */
export function preferH264<T extends { mimeType: string }>(codecs: readonly T[]): T[] {
  const isH264 = (codec: T) => codec.mimeType.toLowerCase() === 'video/h264'
  return [...codecs.filter(isH264), ...codecs.filter((codec) => !isH264(codec))]
}

export const NO_H264_TEXT = '이 브라우저는 H.264 송출을 지원하지 않아 다시보기 · 클립에 영상이 남지 않습니다.'

/** 이 브라우저가 H.264로 송출할 수 있는지. 판단할 수 없으면(getCapabilities 없음) true로 보고 경고하지 않는다 */
export function supportsH264Encoding(): boolean {
  if (typeof RTCRtpSender === 'undefined' || typeof RTCRtpSender.getCapabilities !== 'function') return true
  const codecs = RTCRtpSender.getCapabilities('video')?.codecs
  if (!codecs) return true
  return codecs.some((codec) => codec.mimeType.toLowerCase() === 'video/h264')
}

/** 비디오 transceiver의 코덱 우선순위를 H.264 먼저로. API가 없으면 건너뛴다(브라우저 기본 협상) */
function applyH264Preference(transceiver: RTCRtpTransceiver): void {
  if (typeof transceiver.setCodecPreferences !== 'function' || typeof RTCRtpSender.getCapabilities !== 'function') return
  const codecs = RTCRtpSender.getCapabilities('video')?.codecs
  if (!codecs || codecs.length === 0) return
  try {
    transceiver.setCodecPreferences(preferH264(codecs))
  } catch {
    // 일부 브라우저는 목록을 거절한다. 기본 협상으로 송출은 계속한다
  }
}

export class WhipError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = 'WhipError'
    this.status = status
  }
}

export class WhipClient {
  private readonly url: string
  private readonly bearer: string
  private stream: MediaStream | null = null
  private pc: RTCPeerConnection | null = null
  private sessionUrl: string | null = null
  private closed = false
  private generation = 0
  private retries = 0
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private state: WhipState = 'idle'
  private readonly listeners = new Set<(state: WhipState) => void>()

  constructor(url: string, bearer: string) {
    this.url = url
    this.bearer = bearer
  }

  onState(listener: (state: WhipState) => void): () => void {
    this.listeners.add(listener)
    listener(this.state)
    return () => { this.listeners.delete(listener) }
  }

  getState(): WhipState {
    return this.state
  }

  /** 처음 송출. 서버가 거절하면 WhipError(status)로 실패하고 상태는 'failed'. */
  async publish(stream: MediaStream): Promise<void> {
    this.stream = stream
    this.setState('connecting')
    try {
      await this.attempt()
    } catch (error) {
      if (!this.closed) {
        this.teardown()
        this.setState('failed')
      }
      throw error
    }
  }

  /** 마이크 · 카메라 전환 등으로 트랙을 바꿀 때 재협상 없이 교체한다. */
  async replaceTrack(kind: 'audio' | 'video', track: MediaStreamTrack): Promise<void> {
    const sender = this.pc?.getSenders().find((candidate) => candidate.track?.kind === kind)
    await sender?.replaceTrack(track)
  }

  /** 송출을 끊는다. 이후 재시도는 멈춘다. 서버 종료(POST end)는 부르는 쪽이 이 다음에 한다. */
  close(): void {
    if (this.closed) return
    this.closed = true
    this.generation++
    this.clearRetry()
    this.teardown()
    this.setState('closed')
  }

  private async attempt(): Promise<void> {
    const stream = this.stream
    if (!stream) throw new WhipError(0, '송출할 스트림이 없습니다')
    this.teardown()
    const generation = ++this.generation
    const stale = () => this.closed || generation !== this.generation

    const pc = new RTCPeerConnection({ iceServers: [] })
    this.pc = pc
    for (const track of stream.getTracks()) {
      if (track.kind === 'video') {
        applyH264Preference(pc.addTransceiver(track, { direction: 'sendonly', streams: [stream], sendEncodings: [{ maxBitrate: VIDEO_MAX_BITRATE }] }))
      } else {
        pc.addTransceiver(track, { direction: 'sendonly', streams: [stream] })
      }
    }
    pc.addEventListener('connectionstatechange', () => {
      if (stale()) return
      if (pc.connectionState === 'connected') {
        this.retries = 0
        this.setState('live')
      } else if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
        this.startReconnect()
      }
    })

    await pc.setLocalDescription(await pc.createOffer())
    await waitForIceGathering(pc)
    if (stale()) return
    let response: Response
    try {
      response = await fetch(this.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sdp', Authorization: `Bearer ${this.bearer}` },
        body: pc.localDescription?.sdp ?? '',
      })
    } catch {
      throw new WhipError(0, '송출 서버에 연결하지 못했습니다')
    }
    if (stale()) {
      if (response.ok) discardSession(response, this.url, { Authorization: `Bearer ${this.bearer}` })
      return
    }
    if (response.status !== 201 && response.status !== 200) {
      throw new WhipError(response.status, response.status === 401
        ? '송출이 거절되었습니다. 스트림 키를 확인하거나, 방송을 종료했다면 30초 뒤 다시 시도해 주세요.'
        : `송출 서버 응답 오류(${response.status})`)
    }
    this.sessionUrl = resolveLocation(response.headers.get('Location'), this.url)
    await pc.setRemoteDescription({ type: 'answer', sdp: await response.text() })
  }

  private startReconnect(): void {
    if (this.closed || this.retryTimer) return
    this.setState('reconnecting')
    this.scheduleRetry()
  }

  private scheduleRetry(): void {
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      if (this.closed) return
      // 잠깐 끊겼다가 스스로 돌아왔으면 다시 publish하지 않는다.
      if (this.pc?.connectionState === 'connected') {
        this.retries = 0
        this.setState('live')
        return
      }
      void this.retry()
    }, WHIP_RETRY_MS)
  }

  private async retry(): Promise<void> {
    this.retries++
    try {
      await this.attempt()
    } catch (error) {
      if (this.closed) return
      if (error instanceof WhipError && error.status === 401) {
        this.teardown()
        this.closed = true
        this.setState('ended')
        return
      }
      if (this.retries >= WHIP_MAX_RETRIES) {
        this.teardown()
        this.closed = true
        this.setState('failed')
        return
      }
      this.scheduleRetry()
    }
  }

  private clearRetry(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer)
    this.retryTimer = null
  }

  private teardown(): void {
    const sessionUrl = this.sessionUrl
    this.sessionUrl = null
    if (sessionUrl) {
      void fetch(sessionUrl, { method: 'DELETE', headers: { Authorization: `Bearer ${this.bearer}` } }).catch(() => undefined)
    }
    this.pc?.close()
    this.pc = null
  }

  private setState(state: WhipState): void {
    if (this.state === state) return
    this.state = state
    for (const listener of this.listeners) listener(state)
  }
}
