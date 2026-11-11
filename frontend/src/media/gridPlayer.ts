import Hls from 'hls.js'
import { HlsJsP2PEngine } from 'p2p-media-loader-hlsjs'

// §6.5 그리드 모드: MediaMTX LL-HLS를 hls.js로 받되, 세그먼트를 시청자끼리(WebRTC DataChannel) 나눠 받는다.
// 피어 소개는 같은 서버의 wt-tracker(wss://<host>/tracker). 통계는 1초마다 onStats로 알린다.

import type { GridStats } from './gridStats'

export type { GridStats } from './gridStats'
export { savedPercent } from './gridStats'

export type GridPlayerOptions = {
  trackerUrl?: string
  onStats?: (stats: GridStats) => void
}

export type GridPlayer = {
  /** false면 피어 공유 없이 네이티브 HLS로 재생 중(hls.js · MSE 미지원 기기) */
  readonly p2p: boolean
  close: () => void
}

/**
 * hls.js 설정. p2p-media-loader-hlsjs 2.3.2는 재생목록의 완성된 세그먼트만 P2P 대상으로 등록하고
 * LL-HLS part는 항상 HTTP로 받으므로 lowLatencyMode를 끄고 세그먼트 단위로 받는다(GRID-1).
 * liveSyncDuration을 직접 줘야 엔진이 liveSyncDurationCount를 크게(최대 120초) 늘리지 않는다.
 */
export const GRID_HLS_CONFIG = { lowLatencyMode: false, liveSyncDuration: 4 } as const

export const NETWORK_RETRY_MS = 3000

export function defaultTrackerUrl(loc: Pick<Location, 'protocol' | 'host'> = location): string {
  return `${loc.protocol === 'https:' ? 'wss' : 'ws'}://${loc.host}/tracker`
}

/** 스웜 id는 경로만 쓴다. 기본값(재생목록 절대 URL)이면 localhost · LAN IP · 도메인으로 붙은 시청자가 서로 다른 스웜이 된다. */
export function swarmIdOf(hlsUrl: string): string {
  return new URL(hlsUrl, 'http://placeholder').pathname
}

export function createGridPlayer(video: HTMLVideoElement, hlsUrl: string, options: GridPlayerOptions = {}): GridPlayer {
  if (!Hls.isSupported()) {
    if (!video.canPlayType('application/vnd.apple.mpegurl')) throw new Error('HLS_NOT_SUPPORTED')
    video.src = hlsUrl
    return {
      p2p: false,
      close: () => {
        video.removeAttribute('src')
        video.load()
      },
    }
  }

  const stats = { httpBytes: 0, p2pDownBytes: 0, p2pUpBytes: 0 }
  const peers = new Set<string>()
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let closed = false

  const HlsWithP2P = HlsJsP2PEngine.injectMixin(Hls)
  const hls = new HlsWithP2P({
    ...GRID_HLS_CONFIG,
    p2p: {
      core: {
        announceTrackers: [options.trackerUrl ?? defaultTrackerUrl()],
        swarmId: swarmIdOf(hlsUrl),
      },
      onHlsJsCreated: (created) => {
        const engine = created.p2pEngine
        engine.addEventListener('onChunkDownloaded', (bytes, source) => {
          if (source === 'p2p') stats.p2pDownBytes += bytes
          else stats.httpBytes += bytes
        })
        engine.addEventListener('onChunkUploaded', (bytes) => { stats.p2pUpBytes += bytes })
        engine.addEventListener('onPeerConnect', ({ peerId }) => { peers.add(peerId) })
        engine.addEventListener('onPeerClose', ({ peerId }) => { peers.delete(peerId) })
      },
    },
  })

  hls.on(Hls.Events.ERROR, (_event, data) => {
    if (!data.fatal || closed) return
    if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
      hls.recoverMediaError()
    } else if (!retryTimer) {
      // 방송인 재연결(DISCONNECTED) 등으로 재생목록이 잠깐 없을 때: 3초 뒤 다시 받는다.
      retryTimer = setTimeout(() => {
        retryTimer = null
        if (!closed) hls.startLoad()
      }, NETWORK_RETRY_MS)
    }
  })
  hls.loadSource(hlsUrl)
  hls.attachMedia(video)

  const statsTimer = setInterval(() => options.onStats?.({ ...stats, peers: peers.size }), 1000)

  return {
    p2p: true,
    close: () => {
      if (closed) return
      closed = true
      clearInterval(statsTimer)
      if (retryTimer) clearTimeout(retryTimer)
      hls.destroy()
    },
  }
}
