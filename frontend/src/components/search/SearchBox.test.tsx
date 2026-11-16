import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import SearchBox from './SearchBox'

// 테스트 환경이 node(DOM 없음)라 서버 렌더 결과로 확인한다. 제출 → 이동 · 빈 값 미이동은 searchPath(searchQuery.test.ts)가 정한다.
const render = (url: string) => renderToString(<MemoryRouter initialEntries={[url]}><SearchBox /></MemoryRouter>)

describe('SearchBox', () => {
  it('검색 폼(role=search)과 안내 문구를 그린다', () => {
    const html = render('/')
    expect(html).toContain('role="search"')
    // 서버 렌더에는 창이 없어 useMediaQuery가 "좁음"으로 보고 짧은 placeholder를 쓴다(md 이상은 "채널 · 라이브 · 다시보기 검색")
    expect(html).toContain('placeholder="검색"')
    expect(html).toContain('maxLength="50"')
  })

  it('/search 에서는 주소의 q를 입력창에 채운다', () => {
    expect(render(`/search?q=${encodeURIComponent('게임  방송')}`)).toContain('value="게임 방송"')
  })

  it('다른 페이지에서는 q가 있어도 비운다', () => {
    expect(render('/channel/1?q=abc')).toContain('value=""')
  })
})
