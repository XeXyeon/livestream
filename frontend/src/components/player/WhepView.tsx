import { useEffect, useRef, useState } from 'react'
import type { BroadcastStatus } from '../../api/types'
import { WhepPlayer } from '../../media/whep'
import type { WhepState } from '../../media/whep'
import UnmuteButton from './UnmuteButton'
import VideoControls from './VideoControls'
import type { PlayerChrome } from './VideoControls'
import { useVideoSurface } from './useVideoSurface'

type Props = {
  url: string
  status: BroadcastStatus
  onState?: (state: WhepState) => void
  chrome?: PlayerChrome
}

// §6.5 기본 모드(WHEP) 화면. 소리 있는 자동 재생이 막히면 음소거로 재생하고 "소리 켜기"를 보여 준다.
export default function WhepView({ url, status, onState, chrome }: Props) {
  const videoRef = useRef<HTMLVideoElement>(null)
  const playerRef = useRef<WhepPlayer | null>(null)
  const onStateRef = useRef(onState)
  const [state, setState] = useState<WhepState>('connecting')
  const surface = useVideoSurface(videoRef, chrome?.fill)

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

  return (
    <div className={surface.containerClass} style={surface.containerStyle}>
      <video ref={videoRef} autoPlay playsInline {...surface.videoProps} className="h-full w-full object-contain" />
      {state !== 'playing' && status === 'LIVE' && (
        <div role="status" className="absolute inset-0 flex items-center justify-center bg-black/60 text-sm text-slate-200">
          {state === 'waiting' ? '영상을 기다리는 중…' : '연결 중…'}
        </div>
      )}
      {surface.muted && state === 'playing' && <UnmuteButton onClick={surface.unmute} />}
      <VideoControls videoRef={videoRef} theater={chrome?.theater} onTheaterChange={chrome?.onTheaterChange} />
    </div>
  )
}
