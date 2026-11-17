import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import ClipRangeBar from './ClipRangeBar'

// 테스트 환경이 node(DOM 없음)라 손잡이의 접근성 속성을 서버 렌더로 본다. 끌기 · 키 처리는 clipRange.test.ts의 applyDrag · applyKey
const render = (props: Partial<Parameters<typeof ClipRangeBar>[0]> = {}) => renderToString(
  <ClipRangeBar value={{ start: 70, duration: 30 }} total={300} view={{ start: 10, end: 250 }} onChange={() => {}} {...props} />,
)
const slider = (html: string, label: string) => html.match(new RegExp(`<div role="slider"[^>]*aria-label="${label}"[^>]*>`))?.[0] ?? ''

describe('ClipRangeBar', () => {
  it('손잡이 두 개가 slider로 값 · 범위 · m:ss를 알린다', () => {
    const html = render()
    const start = slider(html, '클립 시작')
    expect(start).toContain('aria-valuenow="70"')
    expect(start).toContain('aria-valuetext="1:10"')
    expect(start).toContain('aria-valuemin="40"') // 끝(100)에서 60초 앞
    expect(start).toContain('aria-valuemax="95"') // 끝에서 5초 앞
    expect(start).toContain('tabindex="0"')
    const end = slider(html, '클립 끝')
    expect(end).toContain('aria-valuenow="100"')
    expect(end).toContain('aria-valuetext="1:40"')
    expect(end).toContain('aria-valuemin="75"')
    expect(end).toContain('aria-valuemax="130"')
  })

  it('손잡이 위치는 창 기준 %, 막대 아래 시각 표시는 없다', () => {
    const html = render()
    expect(html).toContain('left:calc(25% - 30px)') // 시작 (70 − 10) / 240, 블록은 구간 안쪽
    expect(html).toContain('left:calc(37.5% - 14px)') // 끝 (100 − 10) / 240
    expect(html).not.toContain('0:10')
    expect(html).not.toContain('4:10')
  })

  it('재생 위치 표시선은 창 안일 때만', () => {
    expect(render({ currentSeconds: 130 })).toContain('left:50%')
    expect(render({ currentSeconds: 400 })).not.toContain('data-playhead')
  })
})
