// 테스트 전용: node 환경에 없는 WebRTC · MediaStream을 흉내 낸다.
import { vi } from 'vitest'

export class FakeTrack extends EventTarget {
  readonly kind: string
  enabled = true
  readyState: 'live' | 'ended' = 'live'
  stop = vi.fn(() => { this.readyState = 'ended' })
  constructor(kind: string) {
    super()
    this.kind = kind
  }
}

export class FakeMediaStream {
  private readonly tracks: FakeTrack[]
  constructor(tracks: FakeTrack[] = []) {
    this.tracks = [...tracks]
  }
  addTrack(track: FakeTrack) { this.tracks.push(track) }
  getTracks() { return [...this.tracks] }
  getAudioTracks() { return this.tracks.filter((t) => t.kind === 'audio') }
  getVideoTracks() { return this.tracks.filter((t) => t.kind === 'video') }
}

export class FakeSender {
  readonly track: FakeTrack | null
  params: { encodings: { maxBitrate?: number }[] } = { encodings: [{}] }
  replaceTrack = vi.fn(async () => undefined)
  constructor(track: FakeTrack | null) {
    this.track = track
  }
  getParameters() { return this.params }
  setParameters = vi.fn(async (params: { encodings: { maxBitrate?: number }[] }) => { this.params = params })
}

export class FakePeerConnection extends EventTarget {
  static instances: FakePeerConnection[] = []
  iceGatheringState: RTCIceGatheringState = 'complete'
  connectionState: RTCPeerConnectionState = 'new'
  iceConnectionState: RTCIceConnectionState = 'new'
  localDescription: { type: string; sdp: string } | null = null
  remoteDescription: { type: string; sdp: string } | null = null
  readonly transceivers: { kindOrTrack: unknown; init: unknown; sender: FakeSender }[] = []
  close = vi.fn(() => { this.connectionState = 'closed' })

  constructor() {
    super()
    FakePeerConnection.instances.push(this)
  }

  addTransceiver(kindOrTrack: unknown, init: unknown) {
    const sender = new FakeSender(typeof kindOrTrack === 'string' ? null : kindOrTrack as FakeTrack)
    this.transceivers.push({ kindOrTrack, init, sender })
    return { sender }
  }

  getSenders() { return this.transceivers.map((t) => t.sender) }

  async createOffer() { return { type: 'offer', sdp: 'offer-sdp' } }
  async setLocalDescription(description: { type: string; sdp: string }) { this.localDescription = description }
  async setRemoteDescription(description: { type: string; sdp: string }) { this.remoteDescription = description }

  /** 테스트에서 연결 상태 변화를 흉내 낸다. */
  emitConnectionState(state: RTCPeerConnectionState) {
    this.connectionState = state
    this.dispatchEvent(new Event('connectionstatechange'))
  }

  emitIceState(state: RTCIceConnectionState) {
    this.iceConnectionState = state
    this.dispatchEvent(new Event('iceconnectionstatechange'))
  }
}

export function installFakeRtc() {
  FakePeerConnection.instances = []
  vi.stubGlobal('RTCPeerConnection', FakePeerConnection)
  vi.stubGlobal('MediaStream', FakeMediaStream)
}

export const sdpResponse = (status: number, body = 'answer-sdp', location?: string) =>
  new Response(status === 204 ? null : body, { status, headers: location ? { Location: location } : {} })

/** 대기 중인 promise 콜백을 모두 흘려보낸다. */
export const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve() }
