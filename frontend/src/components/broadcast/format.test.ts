import { describe, expect, it } from 'vitest'
import { formatCount, formatDuration, formatRelative } from './format'

describe('format', () => {
  it('시청자 수를 줄여 쓴다', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(1234)).toBe('1,234')
    expect(formatCount(12_345)).toBe('1.2만')
  })

  it('재생 시간을 m:ss · h:mm:ss로 쓴다', () => {
    expect(formatDuration(65)).toBe('1:05')
    expect(formatDuration(3725)).toBe('1:02:05')
    expect(formatDuration(null)).toBe('--:--')
  })

  it('UTC 시각을 상대 시간으로 쓴다', () => {
    const now = Date.parse('2026-11-11T03:00:00Z')
    expect(formatRelative('2026-11-11T02:59:30Z', now)).toBe('방금')
    expect(formatRelative('2026-11-11T02:15:00Z', now)).toBe('45분 전')
    expect(formatRelative('2026-11-11T00:00:00Z', now)).toBe('3시간 전')
    expect(formatRelative('2026-11-09T03:00:00Z', now)).toBe('2일 전')
    expect(formatRelative(null, now)).toBe('')
  })
})
