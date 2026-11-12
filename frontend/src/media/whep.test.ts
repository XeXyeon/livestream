import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FakePeerConnection, flush, installFakeRtc, sdpResponse } from './testRtc'
import { WHEP_RETRY_MS, WhepPlayer } from './whep'
import type { WhepState } from './whep'

describe('WhepPlayer', () => {
  let video: { srcObject: unknown }
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.useFakeTimers()
    installFakeRtc()
    video = { srcObject: null }
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('recvonly offer를 헤더 없이 POST하고 answer를 적용한 뒤 close에서 세션을 DELETE한다', async () => {
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'answer-sdp', '/live/3/whep/abc'))
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }))
    const player = new WhepPlayer()
    player.attach(video as unknown as HTMLVideoElement, 'https://host/live/3/whep')
    await flush()

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('https://host/live/3/whep')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/sdp' })
    expect(init.body).toBe('offer-sdp')
    const pc = FakePeerConnection.instances[0]
    expect(pc.transceivers.map((t) => [t.kindOrTrack, t.init])).toEqual([
      ['video', { direction: 'recvonly' }], ['audio', { direction: 'recvonly' }],
    ])
    expect(pc.remoteDescription).toEqual({ type: 'answer', sdp: 'answer-sdp' })

    const states: WhepState[] = []
    player.onState((state) => states.push(state))
    pc.emitConnectionState('connected')
    player.close()

    expect(states).toEqual(['connecting', 'playing', 'closed'])
    expect(fetchMock).toHaveBeenLastCalledWith('https://host/live/3/whep/abc', { method: 'DELETE' })
    expect(pc.close).toHaveBeenCalled()
  })

  it('송출이 아직 없으면(404) waiting으로 바꾸고 3초 뒤 다시 붙는다', async () => {
    fetchMock.mockResolvedValueOnce(sdpResponse(404, 'not found'))
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'answer-sdp', '/live/3/whep/x'))
    const player = new WhepPlayer()
    player.attach(video as unknown as HTMLVideoElement, 'https://host/live/3/whep')
    await flush()
    expect(player.getState()).toBe('waiting')
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(WHEP_RETRY_MS)
    await flush()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    FakePeerConnection.instances[1].emitConnectionState('connected')
    expect(player.getState()).toBe('playing')
  })

  it('close 뒤에는 재시도하지 않는다', async () => {
    fetchMock.mockResolvedValue(sdpResponse(404, 'not found'))
    const player = new WhepPlayer()
    player.attach(video as unknown as HTMLVideoElement, '/live/3/whep')
    await flush()
    player.close()
    await vi.advanceTimersByTimeAsync(WHEP_RETRY_MS * 3)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(player.getState()).toBe('closed')
  })

  it('연결이 failed가 되면 이전 연결을 닫고 다시 붙는다', async () => {
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'a', '/live/3/whep/1'))
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 200 }))
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'b', '/live/3/whep/2'))
    const player = new WhepPlayer()
    player.attach(video as unknown as HTMLVideoElement, 'https://host/live/3/whep')
    await flush()
    const first = FakePeerConnection.instances[0]
    first.emitConnectionState('connected')
    first.emitConnectionState('failed')
    expect(player.getState()).toBe('waiting')

    await vi.advanceTimersByTimeAsync(WHEP_RETRY_MS)
    await flush()
    expect(fetchMock.mock.calls[1]).toEqual(['https://host/live/3/whep/1', { method: 'DELETE' }])
    expect(FakePeerConnection.instances).toHaveLength(2)
  })
  it('POST 응답 전에 close되면 받은 세션을 DELETE하고, 연결 시도 중 reconnect는 건너뛴다', async () => {
    let resolvePost: (response: Response) => void = () => undefined
    fetchMock.mockImplementationOnce(() => new Promise<Response>((resolve) => { resolvePost = resolve }))
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }))
    const player = new WhepPlayer()
    player.attach(video as unknown as HTMLVideoElement, 'https://host/live/3/whep')
    await flush()
    player.reconnect()
    await flush()
    expect(FakePeerConnection.instances).toHaveLength(1)

    player.close()
    resolvePost(sdpResponse(201, 'answer-sdp', '/live/3/whep/late'))
    await flush()
    expect(fetchMock).toHaveBeenLastCalledWith('https://host/live/3/whep/late', { method: 'DELETE', headers: undefined })
  })
})
