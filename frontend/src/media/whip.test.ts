import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FakeMediaStream, FakePeerConnection, FakeTrack, flush, installFakeRtc, sdpResponse } from './testRtc'
import { WHIP_MAX_RETRIES, WHIP_RETRY_MS, WhipClient, WhipError } from './whip'
import type { WhipState } from './whip'

const URL_ = 'https://host/live/3/whip?src=pc'

describe('WhipClient', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let stream: MediaStream

  beforeEach(() => {
    vi.useFakeTimers()
    installFakeRtc()
    fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    stream = new FakeMediaStream([new FakeTrack('video'), new FakeTrack('audio')]) as unknown as MediaStream
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  async function publishLive(client: WhipClient) {
    await client.publish(stream)
    FakePeerConnection.instances.at(-1)!.emitConnectionState('connected')
  }

  it('Bearer 스트림 키로 sendonly offer를 POST하고 비디오 비트레이트를 3Mbps로 제한한다', async () => {
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'answer', '/live/3/whip/s1'))
    const client = new WhipClient(URL_, 'key-1')
    const states: WhipState[] = []
    client.onState((state) => states.push(state))
    await publishLive(client)

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(URL_)
    expect(init.headers).toEqual({ 'Content-Type': 'application/sdp', Authorization: 'Bearer key-1' })
    const pc = FakePeerConnection.instances[0]
    expect(pc.transceivers.map((t) => (t.init as { direction: string; sendEncodings?: unknown }))).toEqual([
      expect.objectContaining({ direction: 'sendonly', sendEncodings: [{ maxBitrate: 3_000_000 }] }),
      expect.objectContaining({ direction: 'sendonly' }),
    ])
    expect(pc.remoteDescription).toEqual({ type: 'answer', sdp: 'answer' })
    expect(states).toEqual(['idle', 'connecting', 'live'])
  })

  it('처음 송출이 401이면 WhipError로 실패한다', async () => {
    fetchMock.mockResolvedValueOnce(sdpResponse(401, 'no'))
    const client = new WhipClient(URL_, 'bad')
    await expect(client.publish(stream)).rejects.toMatchObject({ status: 401 })
    expect(client.getState()).toBe('failed')
  })

  it('close는 세션을 DELETE하고 연결을 닫으며 이후 재시도하지 않는다', async () => {
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'answer', '/live/3/whip/s1'))
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }))
    const client = new WhipClient(URL_, 'key')
    await publishLive(client)
    const pc = FakePeerConnection.instances[0]
    client.close()
    expect(fetchMock).toHaveBeenLastCalledWith('https://host/live/3/whip/s1', { method: 'DELETE', headers: { Authorization: 'Bearer key' } })
    expect(pc.close).toHaveBeenCalled()

    pc.emitConnectionState('failed')
    await vi.advanceTimersByTimeAsync(WHIP_RETRY_MS * 2)
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(client.getState()).toBe('closed')
  })

  it('끊기면 reconnecting → 3초 뒤 다시 publish → live', async () => {
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'a', '/live/3/whip/s1'))
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 200 }))
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'b', '/live/3/whip/s2'))
    const client = new WhipClient(URL_, 'key')
    await publishLive(client)
    FakePeerConnection.instances[0].emitConnectionState('failed')
    expect(client.getState()).toBe('reconnecting')

    await vi.advanceTimersByTimeAsync(WHIP_RETRY_MS)
    await flush()
    expect(FakePeerConnection.instances).toHaveLength(2)
    FakePeerConnection.instances[1].emitConnectionState('connected')
    expect(client.getState()).toBe('live')
  })

  it('재시도 중 401(종료 차단)을 받으면 바로 멈추고 ended', async () => {
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'a'))
    fetchMock.mockResolvedValue(sdpResponse(401, 'blocked'))
    const client = new WhipClient(URL_, 'key')
    await publishLive(client)
    FakePeerConnection.instances[0].emitConnectionState('failed')
    await vi.advanceTimersByTimeAsync(WHIP_RETRY_MS)
    await flush()
    expect(client.getState()).toBe('ended')
    await vi.advanceTimersByTimeAsync(WHIP_RETRY_MS * 5)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it(`재시도가 ${WHIP_MAX_RETRIES}번 실패하면 failed`, async () => {
    fetchMock.mockResolvedValueOnce(sdpResponse(201, 'a'))
    fetchMock.mockRejectedValue(new TypeError('network'))
    const client = new WhipClient(URL_, 'key')
    await publishLive(client)
    FakePeerConnection.instances[0].emitConnectionState('disconnected')
    for (let i = 0; i < WHIP_MAX_RETRIES; i++) {
      await vi.advanceTimersByTimeAsync(WHIP_RETRY_MS)
      await flush()
    }
    expect(client.getState()).toBe('failed')
    expect(fetchMock).toHaveBeenCalledTimes(1 + WHIP_MAX_RETRIES)
  })

  it('WhipError는 상태 코드를 가진다', () => {
    expect(new WhipError(401, 'x').status).toBe(401)
  })
})
