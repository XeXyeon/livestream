import { createRef } from 'react'
import { renderToString } from 'react-dom/server'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import VideoControls, { controlTip, formatTime } from './VideoControls'
import type { PlayerAction } from './VideoControls'

// 서버 렌더링에서는 툴팁 내용이 그려지지 않으므로, 감싼 버튼 앞에 문구를 적는 가짜로 바꿔 문구를 확인한다
vi.mock('@astryxdesign/core/Tooltip', () => ({
  Tooltip: ({ content, isEnabled = true, children }: { content: string; isEnabled?: boolean; children: ReactNode }) => (
    <span data-tip={content} data-tip-enabled={String(isEnabled)}>{children}</span>
  ),
}))

const tips = (html: string) => [...html.matchAll(/data-tip="([^"]*)"/g)].map((match) => match[1])

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

  it('툴팁: 아이콘 버튼마다 지금 누르면 일어날 일을 짧은 명사 + 단축키로(라이브)', () => {
    const actions: PlayerAction[] = [
      { id: 'grid', label: '그리드 모드(P2P)', icon: <span>G</span>, onClick: () => {} },
      { id: 'clip', label: '클립 만들기', icon: <span>C</span>, onClick: () => {} },
    ]
    const html = renderToString(<VideoControls videoRef={createRef<HTMLVideoElement>()} theater={false} onTheaterChange={() => {}} actions={actions} />)
    // 첫 화면은 재생 중으로 본다(paused 초기값 false)
    expect(tips(html)).toEqual(['일시정지 (K)', '음소거 (M)', '그리드 모드(P2P)', '클립 만들기', '영화관 모드', '전체 화면 (F)'])
    // aria-label과 같은 문구
    expect(html).toMatch(/aria-label="일시정지"/)
    expect(html).toMatch(/aria-label="음소거"/)
    expect(html).not.toContain('title=')
  })

  it('툴팁: 파일 영상은 10초 이동 · 재생 속도, 영화관 모드가 켜져 있으면 종료', () => {
    const html = renderToString(<VideoControls videoRef={createRef<HTMLVideoElement>()} seekable theater onTheaterChange={() => {}} />)
    expect(tips(html)).toEqual(['일시정지 (K)', '10초 뒤로 (J)', '10초 앞으로 (L)', '음소거 (M)', '재생 속도', '영화관 모드 종료', '전체 화면 (F)'])
    expect(html).toMatch(/aria-label="재생 속도 \(1배\)"/)
  })

  it('controlTip: 단축키가 있으면 괄호로 붙인다', () => {
    expect(controlTip('재생', 'K')).toBe('재생 (K)')
    expect(controlTip('화면 속 화면')).toBe('화면 속 화면')
  })
})
