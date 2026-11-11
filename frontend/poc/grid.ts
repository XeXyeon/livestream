// GRID-1 PoC (DESIGN §6.5 gridPlayer · §8). 실제 플레이어(GRID-2)로 옮기기 전에
// LL-HLS 위에서 p2p-media-loader 2.x가 피어 간 바이트를 주고받는지, 지연이 얼마인지,
// iOS Safari가 Opus 오디오를 디코드하는지 측정하는 독립 페이지다. 앱 번들에는 들어가지 않는다.
import Hls from 'hls.js'
import { HlsJsP2PEngine } from 'p2p-media-loader-hlsjs'
import { GRID_P2P_CORE_CONFIG, withStableResponseUrl } from '../src/media/gridPlayer'

const params = new URLSearchParams(location.search)
const channelId = params.get('ch') ?? '1'
const lowLatencyMode = params.get('ll') === '1'
const liveSyncDuration = Number(params.get('sync') ?? (lowLatencyMode ? '1.5' : '6'))
// 재생 위치에서 이 초 안의 세그먼트는 HTTP로 바로 받는다(그보다 앞만 피어 교환). 라이브러리 기본은 15
const highDemandTimeWindow = Number(params.get('hd') ?? GRID_P2P_CORE_CONFIG.highDemandTimeWindow)
// fixurl=0이면 재생목록 주소 정규화를 끈다(수정 전과 비교용)
const fixUrl = params.get('fixurl') !== '0'
const p2pEnabled = params.get('p2p') !== '0'
const nativeMode = params.get('native') === '1'
const useStun = params.get('stun') !== '0'
const hlsUrl = `${location.origin}/live/${channelId}/index.m3u8`
const trackerUrl = params.get('tracker') ?? `wss://${location.host}/tracker`

const video = document.getElementById('video') as HTMLVideoElement
const statsTable = document.getElementById('stats') as HTMLTableElement
const logBox = document.getElementById('log') as HTMLPreElement
const resultBox = document.getElementById('result') as HTMLTextAreaElement

const totals = { httpBytes: 0, p2pDownBytes: 0, p2pUpBytes: 0, httpSegments: 0, p2pSegments: 0 }
const peers = new Set<string>()
const latencySamples: number[] = []
const wallLatencySamples: number[] = []
let codecs = '-'
let trackerErrors = 0
let hlsErrors = 0

function log(message: string) {
  const time = new Date().toISOString().slice(11, 23)
  logBox.textContent = `${time} ${message}\n${logBox.textContent ?? ''}`.slice(0, 20_000)
}

const mb = (bytes: number) => (bytes / 1024 / 1024).toFixed(2)
const avg = (values: number[]) => values.length ? values.reduce((a, b) => a + b, 0) / values.length : NaN

function capabilities() {
  const mms = (window as unknown as { ManagedMediaSource?: typeof MediaSource }).ManagedMediaSource
  return {
    hlsJsSupported: Hls.isSupported(),
    mseOpus: typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported('audio/mp4; codecs="opus"'),
    managedMseOpus: !!mms && mms.isTypeSupported('audio/mp4; codecs="opus"'),
    nativeHls: video.canPlayType('application/vnd.apple.mpegurl'),
  }
}

function audioDecodedBytes(): number | null {
  const value = (video as unknown as { webkitAudioDecodedByteCount?: number }).webkitAudioDecodedByteCount
  return typeof value === 'number' ? value : null
}

let hls: (Hls & { p2pEngine?: HlsJsP2PEngine }) | null = null

function render() {
  const latency = hls?.latency
  const playingDate = hls?.playingDate
  if (typeof latency === 'number' && latency > 0) latencySamples.push(latency)
  if (playingDate) wallLatencySamples.push((Date.now() - playingDate.getTime()) / 1000)
  if (latencySamples.length > 600) latencySamples.shift()
  if (wallLatencySamples.length > 600) wallLatencySamples.shift()
  const downTotal = totals.httpBytes + totals.p2pDownBytes
  const rows: [string, string][] = [
    ['설정', `ch=${channelId} native=${nativeMode} ll=${lowLatencyMode} sync=${liveSyncDuration}s hd=${highDemandTimeWindow}s fixurl=${fixUrl} p2p=${p2pEnabled} stun=${useStun}`],
    ['HLS URL · 트래커', `${hlsUrl} · ${trackerUrl}`],
    ['코덱', codecs],
    ['지원', JSON.stringify(capabilities())],
    ['서버(HTTP)', `${mb(totals.httpBytes)} MB · 세그먼트 ${totals.httpSegments}`],
    ['피어에서 받음', `${mb(totals.p2pDownBytes)} MB · 세그먼트 ${totals.p2pSegments}`],
    ['피어에게 보냄', `${mb(totals.p2pUpBytes)} MB`],
    ['절감', downTotal ? `${((totals.p2pDownBytes / downTotal) * 100).toFixed(1)}%` : '-'],
    ['연결된 피어', `${peers.size}`],
    ['지연(hls.latency)', typeof latency === 'number' ? `${latency.toFixed(2)}s (평균 ${avg(latencySamples).toFixed(2)}s)` : '-'],
    ['지연(PROGRAM-DATE-TIME)', playingDate ? `${((Date.now() - playingDate.getTime()) / 1000).toFixed(2)}s (평균 ${avg(wallLatencySamples).toFixed(2)}s)` : '-'],
    ['오디오 디코드 바이트', `${audioDecodedBytes() ?? '측정 불가'} · muted=${video.muted}`],
    ['오류', `tracker ${trackerErrors} · hls ${hlsErrors}`],
  ]
  statsTable.replaceChildren(...rows.map(([key, value]) => {
    const tr = document.createElement('tr')
    const th = document.createElement('td')
    const td = document.createElement('td')
    th.textContent = key
    td.textContent = value
    tr.append(th, td)
    return tr
  }))
}

function startNative() {
  log('네이티브 HLS: video.src = m3u8')
  video.src = hlsUrl
  video.addEventListener('loadedmetadata', () => {
    const tracks = (video as unknown as { audioTracks?: { length: number } }).audioTracks
    codecs = `native audioTracks=${tracks?.length ?? '알 수 없음'} ${video.videoWidth}x${video.videoHeight}`
  })
  video.addEventListener('error', () => log(`video error ${video.error?.code} ${video.error?.message ?? ''}`))
}

function startHlsJs() {
  const HlsWithP2P = HlsJsP2PEngine.injectMixin(Hls)
  const instance = new HlsWithP2P({
    lowLatencyMode,
    liveSyncDuration,
    ...(fixUrl ? { loader: withStableResponseUrl(Hls.DefaultConfig.loader) } : {}),
    p2p: {
      core: {
        highDemandTimeWindow,
        isP2PDisabled: !p2pEnabled,
        announceTrackers: [trackerUrl],
        // 기본 스웜 id는 재생목록 절대 URL이라 localhost · LAN IP로 붙은 시청자가 서로 다른 스웜이 된다.
        swarmId: `/live/${channelId}/index.m3u8`,
        ...(useStun ? {} : { rtcConfig: { iceServers: [] } }),
      },
      onHlsJsCreated: (created) => {
        const engine = created.p2pEngine
        engine.addEventListener('onChunkDownloaded', (bytes, source) => {
          if (source === 'p2p') totals.p2pDownBytes += bytes
          else totals.httpBytes += bytes
        })
        engine.addEventListener('onChunkUploaded', (bytes) => { totals.p2pUpBytes += bytes })
        engine.addEventListener('onSegmentLoaded', (details) => {
          if (details.downloadSource === 'p2p') totals.p2pSegments++
          else totals.httpSegments++
          log(`segment ${details.downloadSource} ${details.bytesLength}B ${details.segmentUrl.split('/').pop()}${details.peerId ? ` from ${details.peerId}` : ''}`)
        })
        engine.addEventListener('onPeerConnect', ({ peerId, streamType }) => { if (streamType === 'main') peers.add(peerId); log(`peer connect ${streamType} ${peerId}`) })
        engine.addEventListener('onPeerClose', ({ peerId, streamType }) => { if (streamType === 'main') peers.delete(peerId); log(`peer close ${streamType} ${peerId}`) })
        engine.addEventListener('onPeerError', ({ peerId, error }) => log(`peer error ${peerId} ${error.message}`))
        engine.addEventListener('onTrackerError', ({ error }) => { trackerErrors++; log(`tracker error ${String(error)}`) })
        engine.addEventListener('onTrackerWarning', ({ warning }) => log(`tracker warning ${String(warning)}`))
      },
    },
  })
  hls = instance
  instance.on(Hls.Events.MANIFEST_PARSED, (_event, data) => {
    const level = data.levels[0]
    codecs = `video=${level?.videoCodec ?? '?'} audio=${level?.audioCodec ?? '?'} ${level?.width ?? '?'}x${level?.height ?? '?'}`
    log(`manifest parsed: ${codecs}`)
  })
  instance.on(Hls.Events.BUFFER_CODECS, (_event, data) => {
    log(`buffer codecs: ${JSON.stringify({ audio: data.audio?.codec, video: data.video?.codec })}`)
  })
  instance.on(Hls.Events.ERROR, (_event, data) => {
    hlsErrors++
    log(`hls error ${data.type} ${data.details} fatal=${data.fatal}`)
    if (data.fatal && data.type === Hls.ErrorTypes.NETWORK_ERROR) instance.startLoad()
    else if (data.fatal && data.type === Hls.ErrorTypes.MEDIA_ERROR) {
      // 미디어를 다시 붙이면 P2P 엔진이 재생목록 등록을 지우므로 매니페스트도 다시 받는다(gridPlayer와 같음)
      instance.recoverMediaError()
      instance.loadSource(hlsUrl)
    }
  })
  instance.loadSource(hlsUrl)
  instance.attachMedia(video)
}

document.getElementById('unmute')?.addEventListener('click', () => {
  video.muted = false
  void video.play().catch((error: unknown) => log(`play 실패 ${String(error)}`))
})

document.getElementById('report')?.addEventListener('click', () => {
  const downTotal = totals.httpBytes + totals.p2pDownBytes
  const result = {
    userAgent: navigator.userAgent,
    settings: { channelId, nativeMode, lowLatencyMode, liveSyncDuration, highDemandTimeWindow, fixUrl, p2pEnabled, useStun },
    codecs,
    capabilities: capabilities(),
    httpMB: Number(mb(totals.httpBytes)),
    p2pDownMB: Number(mb(totals.p2pDownBytes)),
    p2pUpMB: Number(mb(totals.p2pUpBytes)),
    savedPercent: downTotal ? Number(((totals.p2pDownBytes / downTotal) * 100).toFixed(1)) : 0,
    segments: { http: totals.httpSegments, p2p: totals.p2pSegments },
    peers: peers.size,
    latencyAvgSec: Number(avg(latencySamples).toFixed(2)),
    wallLatencyAvgSec: Number(avg(wallLatencySamples).toFixed(2)),
    audioDecodedBytes: audioDecodedBytes(),
    muted: video.muted,
    errors: { tracker: trackerErrors, hls: hlsErrors },
  }
  resultBox.value = JSON.stringify(result, null, 2)
  void navigator.clipboard?.writeText(resultBox.value).catch(() => undefined)
})

if (nativeMode || !Hls.isSupported()) startNative()
else startHlsJs()
setInterval(render, 1000)
render()
