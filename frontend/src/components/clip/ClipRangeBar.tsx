import { useRef, useState } from 'react'
import type { KeyboardEvent, PointerEvent } from 'react'
import { formatDuration } from '../broadcast/format'
import { applyDrag, applyKey, handleBounds, percentAt, secondsAt } from './clipRange'
import type { ClipDrag, ClipHandle, ClipRangeValue, ClipView } from './clipRange'

type Props = {
  value: ClipRangeValue
  /** 막대 끝(영상 · 초안 길이, 초). 손잡이는 이 안에서만 움직인다 */
  total: number
  /** 막대에 보이는 구간. 라이브는 [0, total], 다시보기는 확대 창 */
  view: ClipView
  /** 재생 위치 표시선(초). 없으면 그리지 않는다 */
  currentSeconds?: number | null
  /** 값이 바뀔 때. handle: 손잡이를 끄는 중 · 키로 옮긴 손잡이(구간 통째 끌기는 'start'), 놓았으면 null */
  onChange: (value: ClipRangeValue, handle: ClipHandle | null) => void
}

const HANDLE_LABEL: Record<ClipHandle, string> = { start: '클립 시작', end: '클립 끝' }

/**
 * 클립 구간 막대(§6.7 "클립 구간 드래그 선택"): 시작 · 끝 손잡이와 그 사이 선택 구간.
 * 포인터 이벤트 + setPointerCapture로 마우스 · 터치 · 펜을 한 코드로 받고, 손잡이는 role="slider"로 키보드를 받는다.
 * 손잡이는 겹치지 않게 시작은 점의 왼쪽, 끝은 오른쪽으로 44px 터치 영역을 둔다(짧은 구간에서도 둘 다 잡힌다).
 */
export default function ClipRangeBar({ value, total, view, currentSeconds = null, onChange }: Props) {
  const trackRef = useRef<HTMLDivElement>(null)
  const [drag, setDrag] = useState<ClipDrag | null>(null)
  const end = value.start + value.duration
  const startPct = percentAt(value.start, view)
  const endPct = percentAt(end, view)

  function begin(event: PointerEvent<HTMLElement>, next: ClipDrag) {
    if (event.button !== 0) return
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    // 끌기를 시작하면 키보드 초점도 그 손잡이로(스크린 리더가 값을 읽게)
    // 마우스 · 터치로 잡을 때는 초점 테두리를 띄우지 않는다(focusVisible: false, 키보드로 오면 그대로 보인다)
    if (next.kind !== 'range') event.currentTarget.focus({ preventScroll: true, focusVisible: false } as FocusOptions)
    setDrag(next)
  }

  function move(event: PointerEvent<HTMLElement>) {
    const rect = trackRef.current?.getBoundingClientRect()
    if (!drag || !rect) return
    const next = applyDrag(drag, event.clientX, rect, view, value, total)
    if (next.start !== value.start || next.duration !== value.duration) onChange(next, drag.kind === 'end' ? 'end' : 'start')
  }

  function finish() {
    if (!drag) return
    setDrag(null)
    onChange(value, null)
  }

  function grabRange(event: PointerEvent<HTMLElement>) {
    const rect = trackRef.current?.getBoundingClientRect()
    if (!rect) return
    begin(event, { kind: 'range', offset: secondsAt(event.clientX, rect, view) - value.start })
  }

  function onKeyDown(handle: ClipHandle, event: KeyboardEvent<HTMLElement>) {
    const next = applyKey(handle, event.key, event.shiftKey, value, total)
    if (!next) return
    event.preventDefault()
    onChange(next, handle)
  }

  const showTip = (handle: ClipHandle) => drag !== null && (drag.kind === handle || drag.kind === 'range')

  return (
    <div className="px-6 pt-7">
      <div ref={trackRef} className="relative h-11 touch-none select-none">
        {/* 막대 바탕 */}
        <div aria-hidden className="absolute inset-x-0 top-1/2 h-2 -translate-y-1/2 rounded-full bg-muted" />
        {/* 선택 구간: 끌면 구간을 통째로 옮긴다 */}
        <div
          aria-hidden
          onPointerDown={grabRange}
          onPointerMove={move}
          onPointerUp={finish}
          onPointerCancel={finish}
          className={`absolute inset-y-2 rounded-sm bg-accent/30 ${drag?.kind === 'range' ? 'cursor-grabbing' : 'cursor-grab'}`}
          style={{ left: `${startPct}%`, width: `${Math.max(0, endPct - startPct)}%` }}
        />
        {currentSeconds !== null && currentSeconds >= view.start && currentSeconds <= view.end && (
          <div aria-hidden className="pointer-events-none absolute inset-y-0 w-0.5 -translate-x-1/2 bg-primary" style={{ left: `${percentAt(currentSeconds, view)}%` }} />
        )}
        {(['start', 'end'] as const).map((handle) => {
          const seconds = handle === 'start' ? value.start : end
          const bounds = handleBounds(handle, value, total)
          return (
            <div
              key={handle}
              role="slider"
              tabIndex={0}
              aria-label={HANDLE_LABEL[handle]}
              aria-orientation="horizontal"
              aria-valuemin={bounds.min}
              aria-valuemax={bounds.max}
              aria-valuenow={seconds}
              aria-valuetext={formatDuration(seconds)}
              onPointerDown={(event) => begin(event, { kind: handle })}
              onPointerMove={move}
              onPointerUp={finish}
              onPointerCancel={finish}
              onKeyDown={(event) => onKeyDown(handle, event)}
              className={`group absolute inset-y-0 flex w-11 cursor-ew-resize items-center rounded-md focus-visible:outline-2 focus-visible:outline-accent ${
                handle === 'start' ? '-translate-x-full justify-end' : 'justify-start'
              }`}
              style={{ left: `${handle === 'start' ? startPct : endPct}%` }}
            >
              {/* 손잡이 모양: 구간 쪽으로 열린 괄호 */}
              <span
                aria-hidden
                className={`block h-9 w-2.5 bg-accent shadow-sm transition-transform duration-150 motion-reduce:transition-none group-active:scale-y-110 ${
                  handle === 'start' ? 'rounded-l-md' : 'rounded-r-md'
                }`}
              />
              {showTip(handle) && (
                <span
                  aria-hidden
                  className={`pointer-events-none absolute -top-7 whitespace-nowrap rounded-md bg-surface px-1.5 py-0.5 text-xs font-medium tabular-nums text-primary shadow-sm ${
                    handle === 'start' ? 'right-0 translate-x-1/2' : 'left-0 -translate-x-1/2'
                  }`}
                >
                  {formatDuration(seconds)}
                </span>
              )}
            </div>
          )
        })}
      </div>
      <div aria-hidden className="mt-1 flex justify-between text-xs tabular-nums text-secondary">
        <span>{formatDuration(view.start)}</span>
        <span>{formatDuration(view.end)}</span>
      </div>
    </div>
  )
}
