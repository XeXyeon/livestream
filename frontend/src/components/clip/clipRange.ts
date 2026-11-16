import { CLIP_MAX_SECONDS, CLIP_MIN_SECONDS } from './clipState'

// 클립 구간 막대(ClipRangeBar)의 규칙(§6.7 "클립 구간 드래그 선택"). 모두 정수 초.
// 제약: 0 ≤ start, end ≤ total, 5 ≤ end − start ≤ 60. 한 손잡이를 한계 너머로 보내면 한계에서 멈추고 다른 손잡이는 그대로다.

export type ClipRangeValue = { start: number; duration: number }
export type ClipHandle = 'start' | 'end'
/** 막대에 보이는 구간(초). 라이브는 초안 전체, 다시보기는 확대 창 */
export type ClipView = { start: number; end: number }

/** 확대 창 앞뒤 여유: 시작 60초 전부터 180초 뒤까지(4분) */
export const ZOOM_BEFORE_SECONDS = 60
export const ZOOM_AFTER_SECONDS = 180

const clamp = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max)

/** 끝을 고정하고 시작을 target 쪽으로 옮길 수 있는 만큼 옮긴다 */
export function clampStart(target: number, end: number): number {
  const min = Math.max(0, end - CLIP_MAX_SECONDS)
  const max = Math.max(min, end - CLIP_MIN_SECONDS)
  return clamp(Math.round(target), min, max)
}

/** 시작을 고정하고 끝을 target 쪽으로 옮길 수 있는 만큼 옮긴다 */
export function clampEnd(target: number, start: number, total: number): number {
  const min = start + CLIP_MIN_SECONDS
  const max = Math.max(min, Math.min(Math.floor(total), start + CLIP_MAX_SECONDS))
  return clamp(Math.round(target), min, max)
}

/** 손잡이 하나를 target(초)로 옮긴 결과. ±Infinity면 그 방향 한계까지 */
export function moveHandle(handle: ClipHandle, target: number, value: ClipRangeValue, total: number): ClipRangeValue {
  const end = value.start + value.duration
  if (handle === 'start') {
    const start = clampStart(target, end)
    return { start, duration: end - start }
  }
  return { start: value.start, duration: clampEnd(target, value.start, total) - value.start }
}

/** 길이를 그대로 두고 구간을 통째로 옮긴다(선택 구간을 끌 때) */
export function shiftRange(targetStart: number, value: ClipRangeValue, total: number): ClipRangeValue {
  const max = Math.max(0, Math.floor(total) - value.duration)
  return { start: clamp(Math.round(targetStart), 0, max), duration: value.duration }
}

/** 손잡이 aria-valuemin · max: 다른 손잡이를 고정했을 때 갈 수 있는 범위 */
export function handleBounds(handle: ClipHandle, value: ClipRangeValue, total: number): { min: number; max: number } {
  if (handle === 'start') {
    const end = value.start + value.duration
    return { min: clampStart(-Infinity, end), max: clampStart(Infinity, end) }
  }
  return { min: clampEnd(-Infinity, value.start, total), max: clampEnd(Infinity, value.start, total) }
}

/** 다시보기 확대 창: [max(0, start − 60), min(total, start + 180)] */
export function zoomWindow(start: number, total: number): ClipView {
  return { start: Math.max(0, start - ZOOM_BEFORE_SECONDS), end: Math.min(Math.floor(total), start + ZOOM_AFTER_SECONDS) }
}

/** 구간이 창 안에 다 보이면 창을 그대로, 아니면 새로 잡는다(입력 · 슬라이더로 창 밖 값을 넣었을 때) */
export function keepWindow(view: ClipView, value: ClipRangeValue, total: number): ClipView {
  const end = value.start + value.duration
  return value.start >= view.start && end <= view.end ? view : zoomWindow(value.start, total)
}

/** 키 한 번의 변화량: ← ↓ −1 · → ↑ +1 (Shift면 5), Home · End는 한계까지. 다른 키는 null */
export function keyDelta(key: string, shiftKey: boolean): number | null {
  const step = shiftKey ? 5 : 1
  switch (key) {
    case 'ArrowLeft':
    case 'ArrowDown':
      return -step
    case 'ArrowRight':
    case 'ArrowUp':
      return step
    case 'Home':
      return -Infinity
    case 'End':
      return Infinity
    default:
      return null
  }
}

/** 포인터 x 좌표 → 막대 위의 초(창 안으로 자르고 1초 단위로 반올림) */
export function secondsAt(clientX: number, rect: { left: number; width: number }, view: ClipView): number {
  if (rect.width <= 0) return view.start
  const ratio = clamp((clientX - rect.left) / rect.width, 0, 1)
  return Math.round(view.start + ratio * (view.end - view.start))
}

/** 초 → 막대 위의 위치(%), 창 밖이면 0 · 100으로 자른다 */
export function percentAt(seconds: number, view: ClipView): number {
  const span = view.end - view.start
  if (span <= 0) return 0
  return clamp(((seconds - view.start) / span) * 100, 0, 100)
}

/** 손잡이의 위치(초). 라이브 모드에서 끄는 동안 초안 영상을 이 위치로 옮긴다 */
export function handleSeconds(value: ClipRangeValue, handle: ClipHandle): number {
  return handle === 'start' ? value.start : value.start + value.duration
}

/** 손잡이에서 키를 눌렀을 때 새 값. 처리하지 않는 키면 null */
export function applyKey(handle: ClipHandle, key: string, shiftKey: boolean, value: ClipRangeValue, total: number): ClipRangeValue | null {
  const delta = keyDelta(key, shiftKey)
  if (delta === null) return null
  return moveHandle(handle, handleSeconds(value, handle) + delta, value, total)
}

/** 끄는 대상: 손잡이 하나, 또는 선택 구간 통째(잡은 지점과 시작의 거리 offset) */
export type ClipDrag = { kind: ClipHandle } | { kind: 'range'; offset: number }

/** 끄는 동안 포인터가 clientX에 있을 때 새 값 */
export function applyDrag(drag: ClipDrag, clientX: number, rect: { left: number; width: number }, view: ClipView, value: ClipRangeValue, total: number): ClipRangeValue {
  const seconds = secondsAt(clientX, rect, view)
  if (drag.kind === 'range') {
    // 창 밖으로 밀려나지 않게 창 안에서만 옮긴다
    const moved = shiftRange(seconds - drag.offset, value, total)
    return { ...moved, start: clamp(moved.start, view.start, Math.max(view.start, view.end - value.duration)) }
  }
  return moveHandle(drag.kind, seconds, value, total)
}
