import { useEffect, useRef, useState } from 'react'
import type { MouseEvent as ReactMouseEvent, ReactNode, RefObject } from 'react'
import { Check, LoaderCircle, Maximize, Minimize, Pause, PictureInPicture2, Play, RectangleHorizontal, RotateCcw, RotateCw, Settings, Volume1, Volume2, VolumeX } from 'lucide-react'
import { Tooltip } from '@astryxdesign/core/Tooltip'
import { useIsFullscreen } from '../common/useMediaQuery'
import PlayerSlider from './PlayerSlider'

/** 컨트롤 줄 오른쪽 묶음 맨 앞에 그리는 아이콘 버튼(시청 화면의 클립 · 그리드 모드) */
export type PlayerAction = {
  id: string
  label: string
  icon: ReactNode
  onClick: () => void
  /** 켜진 상태(강조색 · aria-pressed) */
  pressed?: boolean
  disabled?: boolean
}

type Props = {
  videoRef: RefObject<HTMLVideoElement | null>
  /** 파일 영상(다시보기 · 클립): 탐색 막대 · 시간 · 10초 이동 · 재생 속도를 더한다. 없으면 라이브(LIVE 표시) */
  seekable?: boolean
  /** 영화관 모드 상태와 바꾸기. 없으면 버튼을 그리지 않는다 */
  theater?: boolean
  onTheaterChange?: (theater: boolean) => void
  /** 영화관 · PiP 앞에 그릴 버튼. 전체 화면에서도 보인다 */
  actions?: PlayerAction[]
}

/** Player가 화면(WhepView · GridView)에 넘기는 모양 설정 */
export type PlayerChrome = {
  /** 부모 높이를 채운다(영화관 모드 · 전체 화면) */
  fill?: boolean
  theater?: boolean
  onTheaterChange?: (theater: boolean) => void
  actions?: PlayerAction[]
}

type WebkitVideo = HTMLVideoElement & { webkitEnterFullscreen?: () => void }

const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2]
const IDLE_MS = 2500
const SKIP_SECONDS = 10

/** 초 → "1:05" · "1:02:03" */
export function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '0:00'
  const s = Math.floor(seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/**
 * 영상 위 컨트롤(라이브 · 다시보기 · 클립 공용, 유튜브식). 아래에서부터 그라데이션 위에 [탐색 막대] + 버튼 줄.
 * - 보임: 멈춘 동안, 마우스를 움직인 뒤 2.5초, 컨트롤 위에 있거나 키보드로 들어온 동안. 재생 중 가만히 두면 커서와 함께 숨는다.
 * - 영상 클릭 = 재생 · 정지, 두 번 클릭 = 전체 화면. 터치는 한 번 누르면 컨트롤을 보이고 · 숨긴다.
 * - 단축키(플레이어에 초점이 있을 때): 스페이스 · K 재생, M 소리, F 전체 화면, ←/→ 5초 · J/L 10초(파일 영상).
 * 컨트롤 줄 아이콘 버튼은 마우스를 올리거나 키보드로 초점을 주면 위쪽에 툴팁(aria-label과 같은 문구 + 단축키)을 띄운다.
 *   터치로 누를 때는 띄우지 않는다(한 번에 눌리게). 툴팁은 Popover(최상위 층)라 전체 화면에서도 보인다.
 * PiP(화면 속 화면)는 브라우저가 Picture-in-Picture API를 지원할 때만 보인다(Firefox는 자체 버튼이 있어 숨김).
 * 전체 화면은 영상 칸이 아니라 가장 가까운 `[data-player-root]`(후원 배너까지 포함한 칸)를 띄운다. 요소 전체 화면이 없는 iOS Safari는 영상만 띄운다.
 */
export default function VideoControls({ videoRef, seekable = false, theater, onTheaterChange, actions }: Props) {
  const rootRef = useRef<HTMLDivElement>(null)
  const [paused, setPaused] = useState(false)
  const [waiting, setWaiting] = useState(false)
  // 라이브는 첫 화면이 오기 전에도 paused다. 그동안은 가운데 재생 버튼을 띄우지 않는다(연결 중 안내가 따로 있다)
  const [hasFrame, setHasFrame] = useState(false)
  const [muted, setMuted] = useState(false)
  const [volume, setVolume] = useState(1)
  const [time, setTime] = useState({ current: 0, duration: 0, buffered: 0 })
  const [rate, setRate] = useState(1)
  const [menuOpen, setMenuOpen] = useState(false)
  const [active, setActive] = useState(false)
  const [overBar, setOverBar] = useState(false)
  const [scrubbing, setScrubbing] = useState(false)
  const [pip, setPip] = useState(false)
  // 영상 정보(메타데이터)가 오기 전에는 PiP를 열 수 없다(InvalidStateError)
  const [pipReady, setPipReady] = useState(false)
  const idleTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const fullscreen = useIsFullscreen()
  const pipSupported = typeof document !== 'undefined' && document.pictureInPictureEnabled === true
  const visible = paused || active || overBar || menuOpen || scrubbing

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    const sync = () => {
      setPaused(video.paused)
      setMuted(video.muted || video.volume === 0)
      setVolume(video.volume)
      setRate(video.playbackRate)
      setHasFrame(video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA)
    }
    const syncTime = () => {
      const duration = Number.isFinite(video.duration) ? video.duration : 0
      const ranges = video.buffered
      const buffered = ranges.length > 0 ? ranges.end(ranges.length - 1) : 0
      setTime({ current: video.currentTime, duration, buffered })
    }
    const syncPip = () => {
      setPip(document.pictureInPictureElement === video)
      setPipReady(video.readyState >= HTMLMediaElement.HAVE_METADATA)
    }
    const onWaiting = () => setWaiting(true)
    const onReady = () => setWaiting(false)
    sync()
    syncTime()
    syncPip()
    const events = ['play', 'pause', 'playing', 'volumechange', 'ratechange', 'loadeddata', 'emptied'] as const
    const timeEvents = ['timeupdate', 'durationchange', 'progress', 'loadedmetadata', 'seeking', 'emptied'] as const
    const pipEvents = ['enterpictureinpicture', 'leavepictureinpicture', 'loadedmetadata', 'emptied'] as const
    events.forEach((name) => video.addEventListener(name, sync))
    timeEvents.forEach((name) => video.addEventListener(name, syncTime))
    pipEvents.forEach((name) => video.addEventListener(name, syncPip))
    video.addEventListener('waiting', onWaiting)
    video.addEventListener('playing', onReady)
    video.addEventListener('canplay', onReady)
    return () => {
      events.forEach((name) => video.removeEventListener(name, sync))
      timeEvents.forEach((name) => video.removeEventListener(name, syncTime))
      pipEvents.forEach((name) => video.removeEventListener(name, syncPip))
      video.removeEventListener('waiting', onWaiting)
      video.removeEventListener('playing', onReady)
      video.removeEventListener('canplay', onReady)
    }
  }, [videoRef])

  const wake = () => {
    setActive(true)
    if (idleTimer.current) clearTimeout(idleTimer.current)
    idleTimer.current = setTimeout(() => setActive(false), IDLE_MS)
  }
  const sleep = () => {
    if (idleTimer.current) clearTimeout(idleTimer.current)
    setActive(false)
  }
  useEffect(() => () => { if (idleTimer.current) clearTimeout(idleTimer.current) }, [])

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
    video.volume = Math.min(1, Math.max(0, value))
    video.muted = video.volume === 0
  }

  const seekTo = (seconds: number) => {
    const video = videoRef.current
    if (!video || !Number.isFinite(video.duration)) return
    video.currentTime = Math.min(video.duration, Math.max(0, seconds))
    setTime((prev) => ({ ...prev, current: video.currentTime }))
  }
  const skip = (delta: number) => seekTo((videoRef.current?.currentTime ?? 0) + delta)

  const changeRate = (value: number) => {
    const video = videoRef.current
    if (video) video.playbackRate = value
    setMenuOpen(false)
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

  // 마우스 움직임 · 단축키는 영상 칸 전체(컨트롤의 부모)에서 받는다
  const handlers = useRef({ wake, sleep, togglePlay, toggleMute, toggleFullscreen, skip, changeVolume, seekable })
  handlers.current = { wake, sleep, togglePlay, toggleMute, toggleFullscreen, skip, changeVolume, seekable }
  useEffect(() => {
    const surface = rootRef.current?.parentElement
    if (!surface) return
    const onMove = (event: PointerEvent) => { if (event.pointerType === 'mouse') handlers.current.wake() }
    const onLeave = (event: PointerEvent) => { if (event.pointerType === 'mouse') handlers.current.sleep() }
    const onKey = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey) return
      const target = event.target as HTMLElement
      if (target.closest('input, textarea, select, [contenteditable="true"]')) return
      const h = handlers.current
      const video = videoRef.current
      const key = event.key.toLowerCase()
      // 버튼에 초점이 있으면 스페이스 · 엔터는 그 버튼 몫이다
      if ((key === ' ' || key === 'enter') && target.closest('button, [role="menuitemradio"]')) return
      const actions: Record<string, () => void> = {
        ' ': h.togglePlay,
        k: h.togglePlay,
        m: h.toggleMute,
        f: h.toggleFullscreen,
        arrowup: () => h.changeVolume((video?.volume ?? 1) + 0.1),
        arrowdown: () => h.changeVolume((video?.volume ?? 1) - 0.1),
        ...(h.seekable ? {
          arrowleft: () => h.skip(-5),
          arrowright: () => h.skip(5),
          j: () => h.skip(-SKIP_SECONDS),
          l: () => h.skip(SKIP_SECONDS),
        } : {}),
      }
      const action = actions[key]
      if (!action) return
      event.preventDefault()
      action()
      h.wake()
    }
    surface.addEventListener('pointermove', onMove)
    surface.addEventListener('pointerleave', onLeave)
    surface.addEventListener('keydown', onKey)
    return () => {
      surface.removeEventListener('pointermove', onMove)
      surface.removeEventListener('pointerleave', onLeave)
      surface.removeEventListener('keydown', onKey)
    }
  }, [videoRef])

  // 속도 메뉴: 바깥을 누르거나 Esc면 닫는다
  useEffect(() => {
    if (!menuOpen) return
    const onDown = (event: PointerEvent) => { if (!rootRef.current?.contains(event.target as Node)) setMenuOpen(false) }
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') { event.stopPropagation(); setMenuOpen(false) } }
    document.addEventListener('pointerdown', onDown)
    document.addEventListener('keydown', onKey, true)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      document.removeEventListener('keydown', onKey, true)
    }
  }, [menuOpen])

  const onSurfaceClick = (event: ReactMouseEvent<HTMLDivElement>) => {
    // 터치: 컨트롤을 보이고 숨기기만(재생 · 정지는 버튼으로)
    if ((event.nativeEvent as PointerEvent).pointerType === 'touch') {
      if (visible) sleep()
      else wake()
      return
    }
    togglePlay()
  }

  const VolumeIcon = muted ? VolumeX : volume < 0.5 ? Volume1 : Volume2
  return (
    <>
      {/* 영상 위 클릭 영역(단축키를 받으려고 초점을 받는다) */}
      <div
        tabIndex={0}
        aria-label="영상 플레이어"
        onClick={onSurfaceClick}
        onDoubleClick={(event) => { if ((event.nativeEvent as PointerEvent).pointerType !== 'touch') toggleFullscreen() }}
        className={`absolute inset-0 outline-none ${visible ? '' : 'cursor-none'}`}
      />
      {waiting && !paused && (
        <div aria-hidden className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <LoaderCircle className="h-12 w-12 animate-spin text-white/90 drop-shadow" />
        </div>
      )}
      {paused && (seekable || hasFrame) && (
        <button
          type="button"
          aria-label="재생"
          onClick={togglePlay}
          className="absolute left-1/2 top-1/2 z-10 flex h-16 w-16 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-black/55 text-white backdrop-blur-sm transition hover:scale-105 hover:bg-black/70 focus-visible:outline-2 focus-visible:outline-white"
        >
          <Play aria-hidden className="ml-1 h-8 w-8 fill-current" />
        </button>
      )}
      <div
        ref={rootRef}
        onPointerEnter={(event) => { if (event.pointerType === 'mouse') setOverBar(true) }}
        onPointerLeave={() => setOverBar(false)}
        className={`absolute inset-x-0 bottom-0 z-10 bg-gradient-to-t from-black/85 via-black/40 to-transparent px-3 pb-1.5 pt-12 text-white transition-opacity duration-200 [&:has(:focus-visible)]:opacity-100 ${visible ? 'opacity-100' : 'pointer-events-none opacity-0'}`}
      >
        {seekable && (
          <PlayerSlider
            label="재생 위치"
            value={time.current}
            max={time.duration}
            buffered={time.buffered}
            valueText={`${formatTime(time.current)} / ${formatTime(time.duration)}`}
            step={5}
            onChange={seekTo}
            onScrubStart={() => setScrubbing(true)}
            onScrubEnd={() => setScrubbing(false)}
            hoverLabel={formatTime}
          />
        )}
        <div className="flex items-center gap-0.5">
          <ControlButton label={paused ? '재생' : '일시정지'} shortcut="K" onClick={togglePlay}>
            {paused ? <Play aria-hidden className="h-5 w-5 fill-current" /> : <Pause aria-hidden className="h-5 w-5 fill-current" />}
          </ControlButton>
          {seekable && (
            <>
              <ControlButton label={`${SKIP_SECONDS}초 뒤로`} shortcut="J" onClick={() => skip(-SKIP_SECONDS)} className="hidden sm:inline-flex">
                <RotateCcw aria-hidden className="h-5 w-5" />
              </ControlButton>
              <ControlButton label={`${SKIP_SECONDS}초 앞으로`} shortcut="L" onClick={() => skip(SKIP_SECONDS)} className="hidden sm:inline-flex">
                <RotateCw aria-hidden className="h-5 w-5" />
              </ControlButton>
            </>
          )}
          <div className="group/volume flex items-center">
            <ControlButton label={muted ? '음소거 해제' : '음소거'} shortcut="M" onClick={toggleMute}>
              <VolumeIcon aria-hidden className="h-5 w-5" />
            </ControlButton>
            {/* 소리 크기 막대: 마우스를 올리거나 키보드로 들어오면 펼친다(터치 기기는 기기 음량을 쓴다) */}
            <div className="w-0 overflow-hidden transition-[width] duration-200 group-hover/volume:w-20 focus-within:w-20">
              <PlayerSlider label="소리 크기" value={muted ? 0 : volume} max={1} step={0.1} valueText={`${Math.round((muted ? 0 : volume) * 100)}%`} onChange={changeVolume} className="mx-2 w-16" />
            </div>
          </div>
          {seekable ? (
            <span className="ml-2 whitespace-nowrap text-xs font-medium tabular-nums text-white/90 sm:text-sm">
              {formatTime(time.current)}<span className="text-white/60"> / {formatTime(time.duration)}</span>
            </span>
          ) : (
            <span className="ml-2 inline-flex items-center gap-1.5 text-xs font-bold tracking-wide">
              <span aria-hidden className="h-2 w-2 rounded-full bg-red-500 shadow-[0_0_6px] shadow-red-500" />LIVE
            </span>
          )}
          <div className="flex-1" />
          {actions?.map((action) => (
            <ControlButton key={action.id} label={action.label} onClick={action.onClick} pressed={action.pressed} disabled={action.disabled}>
              {action.icon}
            </ControlButton>
          ))}
          {seekable && (
            <div className="relative">
              <ControlButton label={`재생 속도 (${rate}배)`} tip="재생 속도" tipEnabled={!menuOpen} onClick={() => setMenuOpen(!menuOpen)} pressed={menuOpen}>
                <Settings aria-hidden className={`h-5 w-5 transition-transform duration-200 ${menuOpen ? 'rotate-45' : ''}`} />
                {rate !== 1 && <span className="absolute right-0.5 top-0.5 rounded bg-accent px-0.5 text-[9px] font-bold leading-tight">{rate}x</span>}
              </ControlButton>
              {menuOpen && (
                <div role="menu" aria-label="재생 속도" className="absolute bottom-full right-0 mb-2 min-w-32 rounded-lg bg-black/90 py-1 text-sm shadow-lg backdrop-blur">
                  <p className="px-3 pb-1 pt-1.5 text-xs text-white/60">재생 속도</p>
                  {RATES.map((value) => (
                    <button
                      key={value}
                      type="button"
                      role="menuitemradio"
                      aria-checked={value === rate}
                      onClick={() => changeRate(value)}
                      className="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-white/15 focus-visible:bg-white/15 focus-visible:outline-none"
                    >
                      <Check aria-hidden className={`h-4 w-4 ${value === rate ? '' : 'invisible'}`} />
                      {value === 1 ? '보통' : `${value}x`}
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
          {onTheaterChange && !fullscreen && (
            <ControlButton label={theater ? '영화관 모드 종료' : '영화관 모드'} onClick={() => onTheaterChange(!theater)} className="hidden lg:inline-flex" pressed={theater}>
              <RectangleHorizontal aria-hidden className="h-5 w-5" />
            </ControlButton>
          )}
          {pipSupported && !fullscreen && (
            <ControlButton label={pip ? '화면 속 화면 종료' : '화면 속 화면'} onClick={togglePip} pressed={pip} disabled={!pipReady && !pip}>
              <PictureInPicture2 aria-hidden className="h-5 w-5" />
            </ControlButton>
          )}
          <ControlButton label={fullscreen ? '전체 화면 종료' : '전체 화면'} shortcut="F" onClick={toggleFullscreen}>
            {fullscreen ? <Minimize aria-hidden className="h-5 w-5" /> : <Maximize aria-hidden className="h-5 w-5" />}
          </ControlButton>
        </div>
      </div>
    </>
  )
}

type ControlButtonProps = {
  label: string
  /** 툴팁 문구. 없으면 label */
  tip?: string
  /** 단축키. 툴팁 뒤에 "(K)"처럼 붙인다 */
  shortcut?: string
  /** 메뉴가 열려 있을 때처럼 툴팁이 가리면 끈다 */
  tipEnabled?: boolean
  onClick: () => void
  children: ReactNode
  className?: string
  pressed?: boolean
  disabled?: boolean
}

/** 툴팁 문구: "재생 (K)" */
export function controlTip(label: string, shortcut?: string): string {
  return shortcut ? `${label} (${shortcut})` : label
}

function ControlButton({ label, tip, shortcut, tipEnabled = true, onClick, children, className = '', pressed, disabled }: ControlButtonProps) {
  // Tooltip은 display:contents 래퍼라 줄 배치(hidden sm:inline-flex 등)에 끼어들지 않는다. 터치 탭은 버튼 몫이라 툴팁을 열지 않는다(touchTrigger auto)
  return (
    <Tooltip content={controlTip(tip ?? label, shortcut)} placement="above" isEnabled={tipEnabled}>
      <button
        type="button"
        aria-label={label}
        aria-pressed={pressed}
        disabled={disabled}
        onClick={onClick}
        // 'hidden sm:inline-flex'처럼 표시를 바꿔 넘기면 기본 inline-flex를 빼야 hidden이 먹는다
        className={`relative ${className.includes('hidden') ? '' : 'inline-flex'} h-10 w-10 items-center justify-center rounded-full opacity-90 transition hover:bg-white/15 hover:opacity-100 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent focus-visible:outline-2 focus-visible:outline-white ${pressed ? 'text-accent' : ''} ${className}`}
      >
        {children}
      </button>
    </Tooltip>
  )
}
