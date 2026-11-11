import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

type Listener = (...args: never[]) => void
const fake = vi.hoisted(() => ({
  supported: true,
  engineListeners: new Map<string, (...args: unknown[]) => void>(),
  instances: [] as { config: Record<string, unknown>; handlers: Map<string, (...args: unknown[]) => void>; destroy: ReturnType<typeof import('vitest').vi.fn>; startLoad: ReturnType<typeof import('vitest').vi.fn>; recoverMediaError: ReturnType<typeof import('vitest').vi.fn>; loadSource: ReturnType<typeof import('vitest').vi.fn>; attachMedia: ReturnType<typeof import('vitest').vi.fn> }[],
}))

vi.mock('hls.js', () => {
  class FakeHls {
    static isSupported = () => fake.supported
    static Events = { ERROR: 'hlsError' }
    static ErrorTypes = { NETWORK_ERROR: 'networkError', MEDIA_ERROR: 'mediaError' }
  }
  return { default: FakeHls }
})

vi.mock('p2p-media-loader-hlsjs', () => ({
  HlsJsP2PEngine: {
    injectMixin: () => class {
      readonly config: Record<string, unknown>
      readonly handlers = new Map<string, (...args: unknown[]) => void>()
      destroy = vi.fn()
      startLoad = vi.fn()
      recoverMediaError = vi.fn()
      loadSource = vi.fn()
      attachMedia = vi.fn()
      constructor(config: Record<string, unknown> & { p2p: { onHlsJsCreated: (hls: unknown) => void } }) {
        this.config = config
        fake.instances.push(this)
        config.p2p.onHlsJsCreated({
          p2pEngine: { addEventListener: (name: string, fn: (...args: unknown[]) => void) => fake.engineListeners.set(name, fn) },
        })
      }
      on(name: string, fn: Listener) { this.handlers.set(name, fn as (...args: unknown[]) => void) }
    },
  },
}))

import { GRID_HLS_CONFIG, NETWORK_RETRY_MS, createGridPlayer, defaultTrackerUrl, savedPercent, swarmIdOf } from './gridPlayer'
import type { GridStats } from './gridPlayer'

describe('gridPlayer', () => {
  const video = {} as HTMLVideoElement

  beforeEach(() => {
    vi.useFakeTimers()
    fake.supported = true
    fake.instances.length = 0
    fake.engineListeners.clear()
    vi.stubGlobal('location', { protocol: 'https:', host: 'live.example.com' })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('세그먼트 단위 HLS + 같은 서버 트래커 + 경로 기반 스웜으로 만든다', () => {
    createGridPlayer(video, '/live/3/index.m3u8')
    const { config, loadSource, attachMedia } = fake.instances[0]
    expect(config).toMatchObject({ ...GRID_HLS_CONFIG, p2p: { core: { announceTrackers: ['wss://live.example.com/tracker'], swarmId: '/live/3/index.m3u8' } } })
    expect(loadSource).toHaveBeenCalledWith('/live/3/index.m3u8')
    expect(attachMedia).toHaveBeenCalledWith(video)
  })

  it('서버 · 피어 바이트와 피어 수를 1초마다 알린다', () => {
    const onStats = vi.fn<(stats: GridStats) => void>()
    createGridPlayer(video, '/live/3/index.m3u8', { onStats })
    const on = (name: string, ...args: unknown[]) => fake.engineListeners.get(name)!(...args)
    on('onChunkDownloaded', 1000, 'http')
    on('onChunkDownloaded', 3000, 'p2p', 'peer-a')
    on('onChunkUploaded', 500, 'peer-b')
    on('onPeerConnect', { peerId: 'peer-a' })
    on('onPeerConnect', { peerId: 'peer-b' })
    on('onPeerClose', { peerId: 'peer-b' })
    vi.advanceTimersByTime(1000)
    expect(onStats).toHaveBeenLastCalledWith({ httpBytes: 1000, p2pDownBytes: 3000, p2pUpBytes: 500, peers: 1 })
    expect(savedPercent(onStats.mock.lastCall![0])).toBe(75)
  })

  it('치명적 네트워크 오류면 3초 뒤 다시 받고, 미디어 오류면 복구한다. close 뒤에는 멈춘다', () => {
    const player = createGridPlayer(video, '/live/3/index.m3u8')
    const hls = fake.instances[0]
    const error = hls.handlers.get('hlsError')!
    error('hlsError', { fatal: true, type: 'mediaError' })
    expect(hls.recoverMediaError).toHaveBeenCalled()
    error('hlsError', { fatal: true, type: 'networkError' })
    vi.advanceTimersByTime(NETWORK_RETRY_MS)
    expect(hls.startLoad).toHaveBeenCalledTimes(1)

    error('hlsError', { fatal: true, type: 'networkError' })
    player.close()
    vi.advanceTimersByTime(NETWORK_RETRY_MS)
    expect(hls.startLoad).toHaveBeenCalledTimes(1)
    expect(hls.destroy).toHaveBeenCalled()
  })

  it('hls.js를 못 쓰면 네이티브 HLS(피어 공유 없음)로 재생한다', () => {
    fake.supported = false
    const native = { canPlayType: () => 'maybe', src: '' } as unknown as HTMLVideoElement
    const player = createGridPlayer(native, '/live/3/index.m3u8')
    expect(player.p2p).toBe(false)
    expect(native.src).toBe('/live/3/index.m3u8')
  })

  it('도우미', () => {
    expect(defaultTrackerUrl({ protocol: 'http:', host: 'localhost:5173' })).toBe('ws://localhost:5173/tracker')
    expect(swarmIdOf('https://192.168.0.2/live/7/index.m3u8')).toBe('/live/7/index.m3u8')
    expect(savedPercent({ httpBytes: 0, p2pDownBytes: 0, p2pUpBytes: 0, peers: 0 })).toBe(0)
  })
})
