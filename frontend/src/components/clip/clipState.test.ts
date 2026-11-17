import { describe, expect, it } from 'vitest'
import { clipPlayback, defaultClipDuration, defaultClipStart, defaultLiveClipStart, parseClipTime, validateClipRange } from './clipState'

describe('parseClipTime', () => {
  it('m:ss · h:mm:ss · 초 숫자를 읽는다', () => {
    expect(parseClipTime('1:05')).toBe(65)
    expect(parseClipTime(' 0:00 ')).toBe(0)
    expect(parseClipTime('1:02:03')).toBe(3723)
    expect(parseClipTime('90')).toBe(90)
  })
  it('형식이 틀리면 null', () => {
    expect(parseClipTime('')).toBeNull()
    expect(parseClipTime('1:60')).toBeNull()
    expect(parseClipTime('1:2')).toBeNull()
    expect(parseClipTime('1:60:00')).toBeNull()
    expect(parseClipTime('-5')).toBeNull()
    expect(parseClipTime('abc')).toBeNull()
  })
})

describe('validateClipRange(시작 · 끝)', () => {
  it('범위 안이면 시작 초와 길이(끝 − 시작)를 돌려준다', () => {
    expect(validateClipRange('1:00', '1:30', 120)).toEqual({ ok: true, startSeconds: 60, durationSeconds: 30 })
    expect(validateClipRange('1:30', '2:00', 120)).toEqual({ ok: true, startSeconds: 90, durationSeconds: 30 }) // 끝이 정확히 영상 끝
  })
  it('영상 끝을 넘으면 서버와 같은 문구', () => {
    expect(validateClipRange('1:31', '2:01', 120)).toEqual({ ok: false, message: '클립 구간이 영상 범위를 벗어났습니다.' })
  })
  it('길이는 5~60초, 넘으면 최대 60초 안내', () => {
    expect(validateClipRange('0:00', '0:04', 120).ok).toBe(false)
    expect(validateClipRange('0:10', '0:05', 120).ok).toBe(false) // 끝이 시작보다 앞
    expect(validateClipRange('0:00', '1:01', 120)).toEqual({ ok: false, message: '클립은 최대 60초까지입니다. 지금 61초예요.' })
    expect(validateClipRange('0:00', '0:05', 120).ok).toBe(true)
    expect(validateClipRange('0:00', '1:00', 120).ok).toBe(true)
  })
  it('시작 · 끝 형식이 틀리면 안내', () => {
    expect(validateClipRange('1분', '1:30', 120)).toMatchObject({ ok: false })
    expect(validateClipRange('1:00', '', 120)).toMatchObject({ ok: false, message: '끝 위치는 분:초(예: 1:35) 형식으로 적어 주세요.' })
  })
  it('영상 길이를 모르면 끝 검사는 서버에 맡긴다', () => {
    expect(validateClipRange('99:00', '99:30', null)).toEqual({ ok: true, startSeconds: 5940, durationSeconds: 30 })
  })
  it('라이브 초안(windowSeconds) 안에서만 고를 수 있다', () => {
    expect(validateClipRange('0:07', '0:12', 12)).toMatchObject({ ok: true, startSeconds: 7 })
    expect(validateClipRange('0:08', '0:13', 12)).toEqual({ ok: false, message: '클립 구간이 영상 범위를 벗어났습니다.' })
  })
})

describe('defaultClipDuration', () => {
  it('기본 30초, 짧은 영상 · 초안은 그 길이(최소 5초)', () => {
    expect(defaultClipDuration(null)).toBe(30)
    expect(defaultClipDuration(120)).toBe(30)
    expect(defaultClipDuration(12.9)).toBe(12)
    expect(defaultClipDuration(3)).toBe(5)
  })
})

describe('defaultLiveClipStart', () => {
  it('초안의 끝(지금 직전) 구간을 기본으로 고른다', () => {
    expect(defaultLiveClipStart(120)).toBe(90)
    expect(defaultLiveClipStart(12)).toBe(0)
    expect(defaultLiveClipStart(120, 10)).toBe(110)
  })
})

describe('defaultClipStart', () => {
  it('지금 위치를 쓰되 끝에 가까우면 기본 길이만큼 앞으로 당긴다', () => {
    expect(defaultClipStart(42.7, 600)).toBe(42)
    expect(defaultClipStart(590, 600)).toBe(570)
    expect(defaultClipStart(10, 20)).toBe(0)
    expect(defaultClipStart(500, null)).toBe(500)
  })
})

describe('clipPlayback', () => {
  const base = { status: 'READY', visibility: 'PUBLIC', videoUrl: '/vod/clips/a.mp4', isOwner: false, isCreator: false } as const
  it('상태와 공개 설정에 따라 고른다', () => {
    expect(clipPlayback({ ...base, status: 'PROCESSING', videoUrl: null })).toBe('processing')
    expect(clipPlayback({ ...base, status: 'FAILED', videoUrl: null })).toBe('failed')
    expect(clipPlayback(base)).toBe('public')
    expect(clipPlayback({ ...base, visibility: 'PRIVATE', videoUrl: null, isCreator: true })).toBe('private')
    expect(clipPlayback({ ...base, visibility: 'PRIVATE', videoUrl: null, isOwner: true })).toBe('private')
    expect(clipPlayback({ ...base, visibility: 'PRIVATE', videoUrl: null })).toBe('unavailable')
  })
})
