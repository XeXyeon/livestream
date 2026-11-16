import { useEffect, useRef, useState } from 'react'
import type { ReactNode, RefObject } from 'react'
import { Maximize, Minimize, Pause, PictureInPicture2, Play, RectangleHorizontal, Volume1, Volume2, VolumeX } from 'lucide-react'
import { useIsFullscreen } from '../common/useMediaQuery'

type Props = {
  videoRef: RefObject<HTMLVideoElement | null>
  /** 영화관 모드 상태와 바꾸기. 없으면 버튼을 그리지 않는다 */
  theater?: boolean
  onTheaterChange?: (theater: boolean) => void
}

/** Player가 화면(WhepView · GridView)에 넘기는 모양 설정 */
export type PlayerChrome = {
  /** 부모 높이를 채운다(영화관 모드 · 전체 화면) */
  fill?: boolean
  theater?: boolean
  onTheaterChange?: (theater: boolean) => void
}

type WebkitVideo = HTMLVideoElement & { webkitEnterFullscreen?: () => void }

/**
 * 영상 위 컨트롤(마우스를 올리거나 키보드로 들어오면 보임, 멈춘 동안은 늘 보임): 재생 · 정지, 소리 끄기 · 크기, 영화관 모드, PiP, 전체 화면.
 * PiP(화면 속 화면)는 브라우저가 Picture-in-Picture API를 지원할 때만 보인다(Firefox는 자체 버튼이 있어 숨김). 다른 화면으로 옮기면 영상이 사라지므로 PiP도 닫힌다.
 * 전체 화면은 영상 칸이 아니라 가장 가까운 `[data-player-root]`(후원 배너까지 포함한 칸)를 띄운다. 요소 전체 화면이 없는 iOS Safari는 영상만 띄운다.
 */
export default function VideoControls({ videoRef, theater, onTheaterChange }: Props) {
  const rootRef = useRef<HTMLDivElement>(null)
  const [paused, setPaused] = useState(false)
  const [muted, setMuted] = useState(false)
  const [volume, setVolume] = useState(1)
  const [pip, setPip] = useState(false)
  // 영상 정보(메타데이터)가 오기 전에는 PiP를 열 수 없다(InvalidStateError)
  const [pipReady, setPipReady] = useState(false)
  const fullscreen = useIsFullscreen()
  const pipSupported = typeof document !== 'undefined' && document.pictureInPictureEnabled === true

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const sync = () => {
      setPaused(video.paused)
      setMuted(video.muted || video.volume === 0)
      setVolume(video.volume)
    }
    sync()
    const syncPip = () => {
      setPip(document.pictureInPictureElement === video)
      setPipReady(video.readyState >= HTMLMediaElement.HAVE_METADATA)
    }
    syncPip()
    const events = ['play', 'pause', 'playing', 'volumechange'] as const
    events.forEach((name) => video.addEventListener(name, sync))
    const pipEvents = ['enterpictureinpicture', 'leavepictureinpicture', 'loadedmetadata', 'emptied'] as const
    pipEvents.forEach((name) => video.addEventListener(name, syncPip))
    return () => {
      events.forEach((name) => video.removeEventListener(name, sync))
      pipEvents.forEach((name) => video.removeEventListener(name, syncPip))
    }
  }, [videoRef])

  const togglePlay = () => {
    const video = videoRef.current
    if (!video) return
    if (video.paused) void video.play().catch(() => undefined)
    else video.pause()
  }

  const toggleMute = () => {
    const video = videoRef.current
    if (!video) return
    if (video.muted || video.volume === 0) {
      video.muted = false
      if (video.volume === 0) video.volume = 0.5
    } else {
      video.muted = true
    }
  }

  const changeVolume = (value: number) => {
    const video = videoRef.current
    if (!video) return
    video.volume = value
    video.muted = value === 0
  }

  const togglePip = () => {
    const video = videoRef.current
    if (!video) return
    if (document.pictureInPictureElement === video) void document.exitPictureInPicture().catch(() => undefined)
    else void video.requestPictureInPicture().catch(() => undefined)
  }

  const toggleFullscreen = () => {
    if (document.fullscreenElement) {
      void document.exitFullscreen().catch(() => undefined)
      return
    }
    const target = rootRef.current?.closest<HTMLElement>('[data-player-root]') ?? rootRef.current?.parentElement
    if (target?.requestFullscreen) void target.requestFullscreen().catch(() => undefined)
    else (videoRef.current as WebkitVideo | null)?.webkitEnterFullscreen?.()
  }

  const VolumeIcon = muted ? VolumeX : volume < 0.5 ? Volume1 : Volume2
  return (
    <div
      ref={rootRef}
      className={`absolute inset-x-0 bottom-0 z-10 flex items-center gap-1 bg-gradient-to-t from-black/80 to-transparent px-2 pb-2 pt-10 text-white transition-opacity duration-200 focus-within:opacity-100 group-hover:opacity-100 ${paused ? 'opacity-100' : 'opacity-0'}`}
    >
      <ControlButton label={paused ? '재생' : '정지'} onClick={togglePlay}>
        {paused ? <Play aria-hidden className="h-5 w-5" /> : <Pause aria-hidden className="h-5 w-5" />}
      </ControlButton>
      <div className="group/volume flex items-center">
        <ControlButton label={muted ? '소리 켜기' : '소리 끄기'} onClick={toggleMute}>
          <VolumeIcon aria-hidden className="h-5 w-5" />
        </ControlButton>
        <input
          type="range"
          min={0}
          max={1}
          step={0.05}
          value={muted ? 0 : volume}
          onChange={(event) => changeVolume(Number(event.target.value))}
          aria-label="소리 크기"
          className="h-1 w-20 cursor-pointer accent-white"
        />
      </div>
      <div className="flex-1" />
      {onTheaterChange && !fullscreen && (
        <ControlButton label={theater ? '영화관 모드 끄기' : '영화관 모드'} onClick={() => onTheaterChange(!theater)} className="hidden lg:inline-flex" pressed={theater}>
          <RectangleHorizontal aria-hidden className="h-5 w-5" />
        </ControlButton>
      )}
      {pipSupported && !fullscreen && (
        <ControlButton label={pip ? 'PiP 끝내기' : 'PiP(화면 속 화면)'} onClick={togglePip} pressed={pip} disabled={!pipReady && !pip}>
          <PictureInPicture2 aria-hidden className="h-5 w-5" />
        </ControlButton>
      )}
      <ControlButton label={fullscreen ? '전체 화면 끝내기' : '전체 화면'} onClick={toggleFullscreen}>
        {fullscreen ? <Minimize aria-hidden className="h-5 w-5" /> : <Maximize aria-hidden className="h-5 w-5" />}
      </ControlButton>
    </div>
  )
}

function ControlButton({ label, onClick, children, className = '', pressed, disabled }: { label: string; onClick: () => void; children: ReactNode; className?: string; pressed?: boolean; disabled?: boolean }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex h-9 w-9 items-center justify-center rounded-md hover:bg-white/15 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent focus-visible:outline-2 focus-visible:outline-white ${pressed ? 'text-accent' : ''} ${className}`}
    >
      {children}
    </button>
  )
}
