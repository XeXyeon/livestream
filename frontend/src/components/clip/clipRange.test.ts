import { describe, expect, it } from 'vitest'
import {
  applyDrag, applyKey, clampEnd, clampStart, handleBounds, handleSeconds, keepWindow, keyDelta, moveHandle, percentAt, secondsAt, shiftRange, zoomWindow,
} from './clipRange'

describe('clampStart · clampEnd', () => {
  it('시작은 끝에서 5~60초 앞, 0 아래로 가지 않는다', () => {
    expect(clampStart(40, 100)).toBe(40)
    expect(clampStart(97, 100)).toBe(95) // 5초보다 짧아지지 않음
    expect(clampStart(10, 100)).toBe(40) // 60초보다 길어지지 않음
    expect(clampStart(-3, 30)).toBe(0)
  })
  it('끝은 시작에서 5~60초 뒤, 막대 끝을 넘지 않는다', () => {
    expect(clampEnd(50, 20, 120)).toBe(50)
    expect(clampEnd(21, 20, 120)).toBe(25)
    expect(clampEnd(200, 20, 120)).toBe(80)
    expect(clampEnd(200, 100, 120)).toBe(120)
    expect(clampEnd(119, 100, 119.6)).toBe(119) // 소수 길이(초안 windowSeconds)는 내려서 정수 초
  })
  it('1초 단위로 반올림한다', () => {
    expect(clampStart(40.6, 100)).toBe(41)
    expect(clampEnd(50.4, 20, 120)).toBe(50)
  })
})

describe('moveHandle', () => {
  const value = { start: 30, duration: 20 } // 30 ~ 50
  it('시작 손잡이를 옮기면 끝은 그대로', () => {
    expect(moveHandle('start', 10, value, 120)).toEqual({ start: 10, duration: 40 })
    expect(moveHandle('start', -100, value, 120)).toEqual({ start: 0, duration: 50 })
    expect(moveHandle('start', 49, value, 120)).toEqual({ start: 45, duration: 5 })
  })
  it('끝 손잡이를 옮기면 시작은 그대로', () => {
    expect(moveHandle('end', 70, value, 120)).toEqual({ start: 30, duration: 40 })
    expect(moveHandle('end', Infinity, value, 120)).toEqual({ start: 30, duration: 60 })
    expect(moveHandle('end', -Infinity, value, 120)).toEqual({ start: 30, duration: 5 })
    expect(moveHandle('end', Infinity, value, 45)).toEqual({ start: 30, duration: 15 })
  })
})

describe('shiftRange', () => {
  it('길이를 그대로 두고 0 ~ 막대 끝 안에서 옮긴다', () => {
    expect(shiftRange(50, { start: 30, duration: 20 }, 120)).toEqual({ start: 50, duration: 20 })
    expect(shiftRange(-5, { start: 30, duration: 20 }, 120)).toEqual({ start: 0, duration: 20 })
    expect(shiftRange(110, { start: 30, duration: 20 }, 120)).toEqual({ start: 100, duration: 20 })
  })
})

describe('handleBounds', () => {
  it('다른 손잡이를 고정했을 때 갈 수 있는 범위', () => {
    expect(handleBounds('start', { start: 70, duration: 30 }, 300)).toEqual({ min: 40, max: 95 })
    expect(handleBounds('end', { start: 70, duration: 30 }, 110)).toEqual({ min: 75, max: 110 })
  })
})

describe('zoomWindow · keepWindow', () => {
  it('시작 60초 전 ~ 180초 뒤, 0과 영상 끝에서 자른다', () => {
    expect(zoomWindow(600, 2288)).toEqual({ start: 540, end: 780 })
    expect(zoomWindow(20, 2288)).toEqual({ start: 0, end: 200 })
    expect(zoomWindow(2270, 2288)).toEqual({ start: 2210, end: 2288 })
  })
  it('구간이 창 안이면 창을 그대로, 밖으로 나가면 다시 잡는다', () => {
    const view = { start: 540, end: 780 }
    expect(keepWindow(view, { start: 700, duration: 60 }, 2288)).toBe(view)
    expect(keepWindow(view, { start: 760, duration: 30 }, 2288)).toEqual({ start: 700, end: 940 })
    expect(keepWindow(view, { start: 100, duration: 30 }, 2288)).toEqual({ start: 40, end: 280 })
  })
})

describe('keyDelta', () => {
  it('화살표 1초 · Shift 5초 · Home/End 한계 · 다른 키 null', () => {
    expect(keyDelta('ArrowRight', false)).toBe(1)
    expect(keyDelta('ArrowUp', false)).toBe(1)
    expect(keyDelta('ArrowLeft', true)).toBe(-5)
    expect(keyDelta('ArrowDown', false)).toBe(-1)
    expect(keyDelta('Home', false)).toBe(-Infinity)
    expect(keyDelta('End', true)).toBe(Infinity)
    expect(keyDelta('Enter', false)).toBeNull()
  })
})

describe('secondsAt · percentAt', () => {
  const rect = { left: 100, width: 400 }
  const view = { start: 540, end: 780 }
  it('포인터 위치를 창 안의 정수 초로', () => {
    expect(secondsAt(100, rect, view)).toBe(540)
    expect(secondsAt(300, rect, view)).toBe(660)
    expect(secondsAt(999, rect, view)).toBe(780)
    expect(secondsAt(0, rect, view)).toBe(540)
  })
  it('초를 막대 위 %로, 창 밖은 끝으로 자른다', () => {
    expect(percentAt(660, view)).toBe(50)
    expect(percentAt(100, view)).toBe(0)
    expect(percentAt(900, view)).toBe(100)
  })
})

describe('applyKey · applyDrag', () => {
  const value = { start: 30, duration: 20 } // 30 ~ 50
  const rect = { left: 0, width: 120 }
  const view = { start: 0, end: 120 } // 1px = 1초
  it('키: 시작 손잡이 → 1초 · Shift 5초 · Home 한계, 끝은 그대로', () => {
    expect(applyKey('start', 'ArrowRight', false, value, 120)).toEqual({ start: 31, duration: 19 })
    expect(applyKey('start', 'ArrowLeft', true, value, 120)).toEqual({ start: 25, duration: 25 })
    expect(applyKey('start', 'Home', false, value, 120)).toEqual({ start: 0, duration: 50 })
    expect(applyKey('end', 'End', false, value, 120)).toEqual({ start: 30, duration: 60 })
    expect(applyKey('end', 'Tab', false, value, 120)).toBeNull()
  })
  it('끌기: 한계 너머로 끌면 멈추고 다른 손잡이는 안 움직인다', () => {
    expect(applyDrag({ kind: 'start' }, 10, rect, view, value, 120)).toEqual({ start: 10, duration: 40 })
    expect(applyDrag({ kind: 'start' }, 60, rect, view, value, 120)).toEqual({ start: 45, duration: 5 })
    expect(applyDrag({ kind: 'end' }, 115, rect, view, value, 120)).toEqual({ start: 30, duration: 60 })
  })
  it('구간 끌기: 잡은 지점 기준으로 통째로 옮기고 창 안에 둔다', () => {
    expect(applyDrag({ kind: 'range', offset: 5 }, 75, rect, view, value, 120)).toEqual({ start: 70, duration: 20 })
    expect(applyDrag({ kind: 'range', offset: 5 }, 200, rect, view, value, 120)).toEqual({ start: 100, duration: 20 })
    expect(applyDrag({ kind: 'range', offset: 5 }, 200, rect, { start: 0, end: 90 }, value, 120)).toEqual({ start: 70, duration: 20 })
  })
})

describe('handleSeconds', () => {
  it('시작 손잡이는 시작, 끝 손잡이는 시작 + 길이(라이브 영상이 따라갈 위치)', () => {
    expect(handleSeconds({ start: 30, duration: 20 }, 'start')).toBe(30)
    expect(handleSeconds({ start: 30, duration: 20 }, 'end')).toBe(50)
  })
})
