import { useEffect, useRef, useState } from 'react'
import { Volume2 } from 'lucide-react'
import type { BroadcastStatus } from '../../api/types'
import { WhepPlayer } from '../../media/whep'
import type { WhepState } from '../../media/whep'

type Props = {
  url: string
  status: BroadcastStatus
  onState?: (state: WhepState) => void
}

// §6.5 기본 모드(WHEP) 화면. 소리 있는 자동 재생이 막히면 음소거로 재생하고 "소리 켜기"를 보여 준다.
export default function WhepView({ url, status, onState }: Props) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const playerRef = useRef<WhepPlayer | null>(null)
  const onStateRef = useRef(onState)
  const [state, setState] = useState<WhepState>('connecting')
  const [muted, setMuted] = useState(false)
  const [portrait, setPortrait] = useState<{ w: number; h: number } | null>(null)

  useEffect(() => { onStateRef.current = onState }, [onState])

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const player = new WhepPlayer()
    playerRef.current = player
    const unsubscribe = player.onState((next) => {
      setState(next)
      onStateRef.current?.(next)
    })
    player.attach(video, url)
    return () => {
      unsubscribe()
      player.close()
      playerRef.current = null
    }
  }, [url])

  // 유예(DISCONNECTED) 뒤 다시 LIVE가 되면 3초 재시도를 기다리지 않고 바로 붙는다.
  useEffect(() => {
    if (status === 'LIVE') playerRef.current?.reconnect()
  }, [status])

  function tryPlay() {
    const video = videoRef.current
    if (!video) return
    video.play().catch(() => {
      video.muted = true
      setMuted(true)
      void video.play().catch(() => undefined)
    })
  }

  function handleResize() {
    const video = videoRef.current
    if (!video || !video.videoWidth) return
    setPortrait(video.videoHeight > video.videoWidth ? { w: video.videoWidth, h: video.videoHeight } : null)
  }

  function unmute() {
    const video = videoRef.current
    if (!video) return
    video.muted = false
    setMuted(false)
    void video.play().catch(() => undefined)
  }

  return (
    <div
      className={`relative mx-auto w-full overflow-hidden bg-black ${portrait ? 'max-h-[80vh]' : 'aspect-video'}`}
      style={portrait ? { aspectRatio: `${portrait.w} / ${portrait.h}`, maxWidth: `calc(80vh * ${portrait.w / portrait.h})` } : undefined}
    >
      <video
        ref={videoRef}
        autoPlay
        playsInline
        onLoadedMetadata={() => { handleResize(); tryPlay() }}
        onResize={handleResize}
        className="h-full w-full object-contain"
      />
      {state !== 'playing' && status === 'LIVE' && (
        <div role="status" className="absolute inset-0 flex items-center justify-center bg-black/60 text-sm text-slate-200">
          {state === 'waiting' ? '영상을 기다리는 중…' : '연결 중…'}
        </div>
      )}
      {muted && state === 'playing' && (
        <button type="button" onClick={unmute} className="absolute bottom-3 left-3 flex items-center gap-1.5 rounded-lg bg-black/70 px-3 py-2 text-sm font-semibold text-white hover:bg-black/90">
          <Volume2 aria-hidden className="h-4 w-4" />소리 켜기
        </button>
      )}
    </div>
  )
}
