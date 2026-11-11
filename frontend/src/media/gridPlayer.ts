import Hls from 'hls.js'
import type { HlsConfig, LoaderCallbacks, LoaderConfiguration, LoaderContext } from 'hls.js'
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
 * 라이브 끝에서 6초 뒤를 재생해, 새 세그먼트가 재생 위치에 닿기 전 몇 초 동안 피어끼리 나눌 틈을 만든다.
 * MediaMTX 재생목록은 세그먼트 7개(1초씩)라 이보다 길게 잡을 수 없다.
 */
export const GRID_HLS_CONFIG = { lowLatencyMode: false, liveSyncDuration: 6 } as const

/**
 * p2p-media-loader 코어 설정. 재생 위치에서 highDemandTimeWindow(초) 안의 세그먼트는 곧바로 HTTP로 받고,
 * 그보다 앞의 세그먼트만 피어에게서 받는다(기본 15초면 라이브에서는 모든 세그먼트가 HTTP).
 */
export const GRID_P2P_CORE_CONFIG = { highDemandTimeWindow: 2 } as const

const DELIVERY_DIRECTIVES = ['_HLS_msn', '_HLS_part', '_HLS_skip']

/** LL-HLS 차단 재요청 지시자(`_HLS_msn` 등)를 뺀 재생목록 주소 */
export function stripDeliveryDirectives(url: string): string {
  if (!url.includes('_HLS_')) return url
  try {
    const parsed = new URL(url)
    for (const name of DELIVERY_DIRECTIVES) parsed.searchParams.delete(name)
    return parsed.href
  } catch {
    return url
  }
}

/**
 * hls.js는 lowLatencyMode를 꺼도 서버가 `CAN-BLOCK-RELOAD=YES`면 재생목록을 `?_HLS_msn=…&_HLS_part=…`로 다시 받고,
 * 응답 주소를 재생목록 주소(details.url)로 쓴다. P2P 엔진은 그 주소로 재생목록을 찾으므로 처음 받은 세그먼트 뒤로는
 * 아무것도 등록하지 못해 전부 P2P 밖(HTTP)으로 받는다. 재생목록 응답 주소에서 지시자를 빼 같은 재생목록으로 보이게 한다.
 */
export function withStableResponseUrl(Base: HlsConfig['loader']): HlsConfig['loader'] {
  return class extends Base {
    load(context: LoaderContext, config: LoaderConfiguration, callbacks: LoaderCallbacks<LoaderContext>) {
      if (!('type' in context) || context.type === 'manifest') {
        super.load(context, config, callbacks)
        return
      }
      super.load(context, config, {
        ...callbacks,
        onSuccess: (response, stats, loaded, details) =>
          callbacks.onSuccess({ ...response, url: stripDeliveryDirectives(response.url) }, stats, loaded, details),
      })
    }
  }
}

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
    loader: withStableResponseUrl(Hls.DefaultConfig.loader),
    p2p: {
      core: {
        ...GRID_P2P_CORE_CONFIG,
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
        // 피어 id는 스트림(영상 · 오디오)마다 따로라 영상 스트림의 피어만 센다.
        engine.addEventListener('onPeerConnect', ({ peerId, streamType }) => { if (streamType === 'main') peers.add(peerId) })
        engine.addEventListener('onPeerClose', ({ peerId, streamType }) => { if (streamType === 'main') peers.delete(peerId) })
      },
    },
  })

  hls.on(Hls.Events.ERROR, (_event, data) => {
    if (!data.fatal || closed) return
    if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
      // recoverMediaError는 미디어를 다시 붙이는데, P2P 엔진은 이때 등록한 재생목록을 모두 지우고
      // 매니페스트를 다시 받기 전에는 되살리지 않는다. 그대로 두면 이후 세그먼트가 모두 HTTP로 간다.
      hls.recoverMediaError()
      peers.clear()
      hls.loadSource(hlsUrl)
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
