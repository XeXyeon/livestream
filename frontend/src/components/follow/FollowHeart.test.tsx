import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import FollowHeart from './FollowHeart'

describe('FollowHeart', () => {
  it('팔로잉: 채운 하트 · aria-pressed=true · "팔로우 취소" · 천 단위 수 · title "팔로워 N명"', () => {
    const html = renderToString(<FollowHeart count={1234} following onClick={() => {}} />)
    expect(html).toMatch(/<button[^>]*aria-label="팔로우 취소"/)
    expect(html).toContain('aria-pressed="true"')
    expect(html).toContain('title="팔로워 1,234명"')
    expect(html).toContain('fill-current text-rose-500')
    expect(html).toContain('>1,234<')
  })

  it('팔로우 전: 선 하트 · aria-pressed=false · "팔로우"', () => {
    const html = renderToString(<FollowHeart count={7} onClick={() => {}} />)
    expect(html).toMatch(/<button[^>]*aria-label="팔로우"/)
    expect(html).toContain('aria-pressed="false"')
    expect(html).not.toContain('fill-current')
    expect(html).not.toMatch(/ disabled(=|>| )/)
    expect(html).toContain('>7<')
  })

  it('요청 중 · 목록 로딩 중이면 누를 수 없다', () => {
    expect(renderToString(<FollowHeart count={1} disabled onClick={() => {}} />)).toMatch(/ disabled=""/)
  })

  it('onClick이 없으면(자기 채널 등) 버튼이 아니라 누를 수 없는 span이고 수만 보인다', () => {
    const html = renderToString(<FollowHeart count={42} />)
    expect(html).not.toContain('<button')
    expect(html).not.toContain('aria-pressed')
    expect(html).toMatch(/^<span[^>]*title="팔로워 42명"/)
    expect(html).toContain('>42<')
  })
})
