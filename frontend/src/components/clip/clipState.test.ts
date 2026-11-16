import { describe, expect, it } from 'vitest'
import { clipPlayback, defaultClipStart, parseClipTime, validateClipRange } from './clipState'

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

describe('validateClipRange', () => {
  it('범위 안이면 시작 초를 돌려준다', () => {
    expect(validateClipRange('1:00', 30, 120)).toEqual({ ok: true, startSeconds: 60 })
    expect(validateClipRange('1:30', 30, 120)).toEqual({ ok: true, startSeconds: 90 }) // 끝이 정확히 영상 끝
  })
  it('영상 끝을 넘으면 서버와 같은 문구', () => {
    expect(validateClipRange('1:31', 30, 120)).toEqual({ ok: false, message: '클립 구간이 영상 범위를 벗어났습니다.' })
  })
  it('길이는 5~60초', () => {
    expect(validateClipRange('0:00', 4, 120).ok).toBe(false)
    expect(validateClipRange('0:00', 61, 120).ok).toBe(false)
    expect(validateClipRange('0:00', 5, 120).ok).toBe(true)
    expect(validateClipRange('0:00', 60, 120).ok).toBe(true)
  })
  it('시작 형식이 틀리면 안내', () => {
    expect(validateClipRange('1분', 30, 120)).toMatchObject({ ok: false })
  })
  it('영상 길이를 모르면 끝 검사는 서버에 맡긴다', () => {
    expect(validateClipRange('99:00', 30, null)).toEqual({ ok: true, startSeconds: 5940 })
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
