import { useRef, useState } from 'react'
import type { KeyboardEvent, PointerEvent } from 'react'

type Props = {
  label: string
  /** 0~max */
  value: number
  max: number
  /** 읽어 주는 값(예: "1:23 / 4:56") */
  valueText?: string
  /** 미리 받아 둔 끝(탐색 막대의 버퍼) */
  buffered?: number
  /** 화살표 한 번에 움직이는 양 */
  step: number
  onChange: (value: number) => void
  /** 끌기 시작 · 끝(끄는 동안 재생을 잠깐 세울 때) */
  onScrubStart?: () => void
  onScrubEnd?: () => void
  /** 마우스를 올린 위치의 말풍선 글자. 없으면 말풍선을 그리지 않는다 */
  hoverLabel?: (value: number) => string
  className?: string
}

/**
 * 플레이어 막대(탐색 · 소리 크기). 브라우저 기본 range는 모양을 맞추기 어려워 직접 그린다.
 * 얇게 있다가 마우스를 올리거나 끄는 동안 두꺼워지고 동그란 손잡이가 보인다. 키보드: ←/→, Home/End.
 */
export default function PlayerSlider({ label, value, max, valueText, buffered, step, onChange, onScrubStart, onScrubEnd, hoverLabel, className = '' }: Props) {
  const trackRef = useRef<HTMLDivElement>(null)
  const [dragging, setDragging] = useState(false)
  const [hover, setHover] = useState<number | null>(null)

  const ratioOf = (v: number) => (max > 0 ? Math.min(1, Math.max(0, v / max)) : 0)
  const valueAt = (clientX: number) => {
    const rect = trackRef.current?.getBoundingClientRect()
    if (!rect || rect.width === 0) return 0
    return Math.min(1, Math.max(0, (clientX - rect.left) / rect.width)) * max
  }

  const onPointerDown = (event: PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0 || max <= 0) return
    event.currentTarget.setPointerCapture?.(event.pointerId)
    setDragging(true)
    onScrubStart?.()
    onChange(valueAt(event.clientX))
  }
  const onPointerMove = (event: PointerEvent<HTMLDivElement>) => {
    if (event.pointerType === 'mouse') setHover(valueAt(event.clientX))
    if (dragging) onChange(valueAt(event.clientX))
  }
  const finish = () => {
    if (!dragging) return
    setDragging(false)
    onScrubEnd?.()
  }

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const next = {
      ArrowLeft: value - step,
      ArrowDown: value - step,
      ArrowRight: value + step,
      ArrowUp: value + step,
      Home: 0,
      End: max,
    }[event.key]
    if (next === undefined) return
    event.preventDefault()
    // 플레이어 단축키(←/→ 5초)와 겹치지 않게 여기서 멈춘다
    event.stopPropagation()
    onChange(Math.min(max, Math.max(0, next)))
  }

  const played = ratioOf(value) * 100
  const loaded = buffered === undefined ? 0 : ratioOf(buffered) * 100
  const active = dragging || hover !== null
  return (
    <div
      ref={trackRef}
      role="slider"
      tabIndex={0}
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={Math.round(max)}
      aria-valuenow={Math.round(value)}
      aria-valuetext={valueText}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={finish}
      onPointerCancel={finish}
      onPointerLeave={() => setHover(null)}
      onKeyDown={onKeyDown}
      className={`group/slider relative flex h-4 cursor-pointer touch-none items-center outline-none ${className}`}
    >
      <div className={`relative h-1 w-full overflow-hidden rounded-full bg-white/25 transition-[height] duration-100 group-focus-visible/slider:h-1.5 ${active ? 'h-1.5' : ''}`}>
        {buffered !== undefined && <div className="absolute inset-y-0 left-0 bg-white/40" style={{ width: `${loaded}%` }} />}
        <div className="absolute inset-y-0 left-0 bg-accent" style={{ width: `${played}%` }} />
      </div>
      <div
        aria-hidden
        className={`absolute top-1/2 h-3.5 w-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-accent shadow transition-transform duration-100 group-focus-visible/slider:scale-100 group-focus-visible/slider:ring-2 group-focus-visible/slider:ring-white ${active ? 'scale-100' : 'scale-0'}`}
        style={{ left: `${played}%` }}
      />
      {hoverLabel && hover !== null && (
        <div
          aria-hidden
          className="pointer-events-none absolute bottom-full mb-2 -translate-x-1/2 rounded bg-black/85 px-1.5 py-0.5 text-xs font-semibold tabular-nums text-white"
          style={{ left: `clamp(1.5rem, ${ratioOf(hover) * 100}%, calc(100% - 1.5rem))` }}
        >
          {hoverLabel(hover)}
        </div>
      )}
    </div>
  )
}
