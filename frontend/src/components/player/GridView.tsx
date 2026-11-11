import { useEffect, useRef, useState } from 'react'
import type { GridPlayer } from '../../media/gridPlayer'
import type { GridStats } from '../../media/gridStats'
import UnmuteButton from './UnmuteButton'
import { useVideoSurface } from './useVideoSurface'

type Props = {
  hlsUrl: string
  onStats: (stats: GridStats | null) => void
  onP2pUnavailable?: () => void
}

// §6.5 그리드 모드 화면. hls.js + p2p-media-loader는 크기가 커서(수백 KB) 그리드 모드에 들어올 때만 불러온다.
export default function GridView({ hlsUrl, onStats, onP2pUnavailable }: Props) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const callbacks = useRef({ onStats, onP2pUnavailable })
  const [playing, setPlaying] = useState(false)
  const [error, setError] = useState('')
  const surface = useVideoSurface(videoRef)

  useEffect(() => { callbacks.current = { onStats, onP2pUnavailable } }, [onStats, onP2pUnavailable])

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    let player: GridPlayer | null = null
    let cancelled = false
    void import('../../media/gridPlayer').then(({ createGridPlayer }) => {
      if (cancelled) return
      try {
        player = createGridPlayer(video, hlsUrl, { onStats: (stats) => callbacks.current.onStats(stats) })
        if (!player.p2p) callbacks.current.onP2pUnavailable?.()
      } catch {
        setError('이 브라우저는 그리드 모드를 재생할 수 없습니다. 기본 모드를 써 주세요.')
      }
    }).catch(() => setError('그리드 플레이어를 불러오지 못했습니다.'))
    return () => {
      cancelled = true
      player?.close()
      callbacks.current.onStats(null)
    }
  }, [hlsUrl])

  return (
    <div className={surface.containerClass} style={surface.containerStyle}>
      <video
        ref={videoRef}
        autoPlay
        playsInline
        {...surface.videoProps}
        onPlaying={() => setPlaying(true)}
        onWaiting={() => setPlaying(false)}
        className="h-full w-full object-contain"
      />
      {!playing && !error && (
        <div role="status" className="absolute inset-0 flex items-center justify-center bg-black/60 text-sm text-slate-200">그리드 연결 중…</div>
      )}
      {error && <div role="alert" className="absolute inset-0 flex items-center justify-center bg-black/80 px-6 text-center text-sm text-rose-200">{error}</div>}
      {surface.muted && playing && <UnmuteButton onClick={surface.unmute} />}
    </div>
  )
}
