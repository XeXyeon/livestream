import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPcStream } from './mixer'
import { FakeMediaStream, FakeTrack } from './testRtc'

class FakeNode {
  connected: unknown[] = []
  gain = { value: 1 }
  fftSize = 0
  connect(node: unknown) { this.connected.push(node) }
  disconnect = vi.fn(() => { this.connected = [] })
  getByteTimeDomainData(buffer: Uint8Array) { buffer.fill(128) }
}

class FakeAudioContext {
  static last: FakeAudioContext
  readonly outTrack = new FakeTrack('audio')
  readonly sources: { stream: FakeMediaStream; node: FakeNode }[] = []
  readonly gains: FakeNode[] = []
  close = vi.fn(async () => undefined)
  resume = vi.fn(async () => undefined)
  constructor() { FakeAudioContext.last = this }
  createMediaStreamDestination() { return { stream: new FakeMediaStream([this.outTrack]) } }
  createGain() { const node = new FakeNode(); this.gains.push(node); return node }
  createAnalyser() { return new FakeNode() }
  createMediaStreamSource(stream: FakeMediaStream) { const node = new FakeNode(); this.sources.push({ stream, node }); return node }
}

describe('createPcStream', () => {
  let display: FakeMediaStream
  let micTrack: FakeTrack
  let getUserMedia: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.stubGlobal('MediaStream', FakeMediaStream)
    vi.stubGlobal('AudioContext', FakeAudioContext)
    micTrack = new FakeTrack('audio')
    getUserMedia = vi.fn(async () => new FakeMediaStream([micTrack]))
  })

  afterEach(() => vi.unstubAllGlobals())

  function stubDevices(withSystemAudio: boolean) {
    display = new FakeMediaStream(withSystemAudio ? [new FakeTrack('video'), new FakeTrack('audio')] : [new FakeTrack('video')])
    vi.stubGlobal('navigator', { mediaDevices: { getDisplayMedia: vi.fn(async () => display), getUserMedia } })
  }

  it('화면 영상 1개 + 섞은 오디오 1개를 만들고 탭 소리 · 마이크를 모두 연결한다', async () => {
    stubDevices(true)
    const result = await createPcStream({ mic: '' })
    const tracks = result.stream.getTracks() as unknown as FakeTrack[]
    expect(tracks.map((t) => t.kind)).toEqual(['video', 'audio'])
    expect(tracks[1]).toBe(FakeAudioContext.last.outTrack)
    expect(result.warnings).toEqual([])
    expect(FakeAudioContext.last.sources).toHaveLength(2)
    expect(getUserMedia).toHaveBeenCalledWith({ audio: { echoCancellation: true, noiseSuppression: true } })
  })

  it('탭 소리를 공유하지 않으면 NO_SYSTEM_AUDIO, 마이크를 못 열면 MIC_UNAVAILABLE', async () => {
    stubDevices(false)
    getUserMedia.mockRejectedValueOnce(new DOMException('denied', 'NotAllowedError'))
    const result = await createPcStream({ mic: 'mic-1' })
    expect(result.warnings).toEqual(['NO_SYSTEM_AUDIO', 'MIC_UNAVAILABLE'])
    expect(result.hasMic).toBe(false)
  })

  it('공유 중지(video ended)면 onEnded, stop은 모든 트랙을 멈추고 AudioContext를 닫는다', async () => {
    stubDevices(true)
    const onEnded = vi.fn()
    const result = await createPcStream({ mic: '', onEnded })
    display.getVideoTracks()[0].dispatchEvent(new Event('ended'))
    expect(onEnded).toHaveBeenCalledTimes(1)

    result.stop()
    expect(display.getTracks().every((t) => t.stop.mock.calls.length === 1)).toBe(true)
    expect(micTrack.stop).toHaveBeenCalled()
    expect(FakeAudioContext.last.outTrack.stop).toHaveBeenCalled()
    expect(FakeAudioContext.last.close).toHaveBeenCalled()
  })

  it('볼륨과 마이크 끄기를 바꿀 수 있다', async () => {
    stubDevices(true)
    const result = await createPcStream({ mic: '' })
    const [systemGain, micGain] = FakeAudioContext.last.gains
    result.setSystemGain(0)
    result.setMicGain(1.5)
    expect(systemGain.gain.value).toBe(0)
    expect(micGain.gain.value).toBe(1.5)
    await result.setMic(null)
    expect(micTrack.stop).toHaveBeenCalled()
    expect(result.hasMic).toBe(false)
  })
})
