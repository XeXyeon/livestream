import { createRef } from 'react'
import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import VideoControls, { formatTime } from './VideoControls'

describe('formatTime', () => {
  it('분:초, 한 시간이 넘으면 시:분:초', () => {
    expect(formatTime(0)).toBe('0:00')
    expect(formatTime(65.9)).toBe('1:05')
    expect(formatTime(3723)).toBe('1:02:03')
    expect(formatTime(Number.NaN)).toBe('0:00')
  })
})

describe('VideoControls', () => {
  const render = (seekable: boolean) => renderToString(<VideoControls videoRef={createRef<HTMLVideoElement>()} seekable={seekable} />)

  it('라이브는 LIVE 표시만, 탐색 막대 · 속도 없음', () => {
    const html = render(false)
    expect(html).toContain('LIVE')
    expect(html).not.toContain('재생 위치')
    expect(html).not.toContain('재생 속도')
  })

  it('파일 영상은 탐색 막대 · 시간 · 10초 이동 · 재생 속도', () => {
    const html = render(true)
    expect(html).toMatch(/role="slider"[^>]*aria-label="재생 위치"/)
    expect(html).toContain('10초 뒤로')
    expect(html).toContain('재생 속도')
    expect(html).not.toContain('LIVE')
  })
})
