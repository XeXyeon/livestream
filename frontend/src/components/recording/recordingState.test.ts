import { describe, expect, it } from 'vitest'
import { canChangeVisibility, recordingPlayback } from './recordingState'

const base = { status: 'READY', visibility: 'PUBLIC', videoUrl: '/vod/a.mp4', isOwner: false } as const

describe('recordingPlayback', () => {
  it('공개 READY는 videoUrl로 재생', () => {
    expect(recordingPlayback(base)).toBe('public')
  })
  it('비공개 READY는 주인만 토큰 재생', () => {
    expect(recordingPlayback({ ...base, visibility: 'PRIVATE', videoUrl: null, isOwner: true })).toBe('private')
    expect(recordingPlayback({ ...base, visibility: 'PRIVATE', videoUrl: null, isOwner: false })).toBe('unavailable')
  })
  it('공개인데 주소가 없으면 재생 불가', () => {
    expect(recordingPlayback({ ...base, videoUrl: null, isOwner: true })).toBe('unavailable')
  })
  it('READY가 아니면 상태별 안내', () => {
    expect(recordingPlayback({ ...base, status: 'RECORDING' })).toBe('processing')
    expect(recordingPlayback({ ...base, status: 'PROCESSING' })).toBe('processing')
    expect(recordingPlayback({ ...base, status: 'FAILED' })).toBe('failed')
    expect(recordingPlayback({ ...base, status: 'FILE_DELETED', videoUrl: null })).toBe('deleted')
  })
})

describe('canChangeVisibility', () => {
  it('파일이 없는 상태에서는 스위치를 숨긴다', () => {
    expect(canChangeVisibility('READY')).toBe(true)
    expect(canChangeVisibility('PROCESSING')).toBe(true)
    expect(canChangeVisibility('FAILED')).toBe(false)
    expect(canChangeVisibility('FILE_DELETED')).toBe(false)
  })
})
