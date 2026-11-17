import { createRef } from 'react'
import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import VideoControls, { formatTime } from './VideoControls'
import type { PlayerAction } from './VideoControls'

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
  it('actions: 오른쪽 묶음에 aria-label · aria-pressed 버튼으로 그려진다(영화관 · PiP 앞)', () => {
    const actions: PlayerAction[] = [
      { id: 'grid', label: '그리드 모드(P2P)', icon: <span>G</span>, onClick: () => {}, pressed: true },
      { id: 'clip', label: '클립 만들기', icon: <span>C</span>, onClick: () => {}, disabled: true },
    ]
    const html = renderToString(<VideoControls videoRef={createRef<HTMLVideoElement>()} theater={false} onTheaterChange={() => {}} actions={actions} />)
    expect(html).toMatch(/<button[^>]*aria-label="그리드 모드\(P2P\)"[^>]*aria-pressed="true"/)
    const clip = html.match(/<button[^>]*aria-label="클립 만들기"[^>]*>/)?.[0] ?? ''
    expect(clip).toContain('disabled')
    expect(clip).not.toContain('aria-pressed')
    expect(html.indexOf('클립 만들기')).toBeLessThan(html.indexOf('영화관 모드'))
  })

  it('actions가 없으면 버튼을 더하지 않는다', () => {
    const html = render(false)
    expect(html).not.toContain('클립 만들기')
    expect(html).not.toContain('그리드')
  })
})
