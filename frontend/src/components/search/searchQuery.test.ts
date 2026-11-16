import { describe, expect, it } from 'vitest'
import { normalizeQuery, readSearchParams, SEARCH_MAX_LENGTH, searchPath } from './searchQuery'

describe('normalizeQuery', () => {
  it('앞뒤 공백을 떼고 연속 공백을 한 칸으로 줄인다', () => {
    expect(normalizeQuery('  게임   방송 \t 모음  ')).toBe('게임 방송 모음')
  })

  it('공백뿐이면 빈 문자열이다', () => {
    expect(normalizeQuery('   \n ')).toBe('')
    expect(normalizeQuery('')).toBe('')
  })

  it('50자를 넘으면 앞 50자까지만 남긴다(글자 단위)', () => {
    expect(normalizeQuery('가'.repeat(60))).toBe('가'.repeat(SEARCH_MAX_LENGTH))
    expect(Array.from(normalizeQuery('😀'.repeat(60)))).toHaveLength(SEARCH_MAX_LENGTH)
  })

  it('자른 끝이 공백이면 그 공백도 뗀다', () => {
    expect(normalizeQuery(`${'a'.repeat(49)} bcd`)).toBe('a'.repeat(49))
  })
})

describe('searchPath', () => {
  it('정리한 검색어를 인코딩해 /search?q= 주소를 만든다', () => {
    expect(searchPath('  게임 & 노래 ')).toBe(`/search?q=${encodeURIComponent('게임 & 노래')}`)
    expect(searchPath('a%b_c')).toBe('/search?q=a%25b_c')
  })

  it('빈 값이면 null(이동하지 않는다)', () => {
    expect(searchPath('')).toBeNull()
    expect(searchPath('   ')).toBeNull()
  })

  it('종류별 목록은 type을, 둘째 쪽부터는 page를 붙인다', () => {
    expect(searchPath('abc', 'lives')).toBe('/search?q=abc&type=lives')
    expect(searchPath('abc', 'recordings', 0)).toBe('/search?q=abc&type=recordings')
    expect(searchPath('abc', 'channels', 2)).toBe('/search?q=abc&type=channels&page=2')
  })
})

describe('readSearchParams', () => {
  const read = (query: string) => readSearchParams(new URLSearchParams(query))

  it('q 없음 · 공백뿐이면 빈 q', () => {
    expect(read('')).toEqual({ q: '', type: 'all', page: 0 })
    expect(read('q=%20%20')).toEqual({ q: '', type: 'all', page: 0 })
  })

  it('q는 정리해 읽고 인코딩된 값을 풀어 읽는다', () => {
    expect(read(`q=${encodeURIComponent('  게임   방송 ')}`).q).toBe('게임 방송')
    expect(read('q=a%25b').q).toBe('a%b')
  })

  it('type은 세 종류만 받고 그 밖은 all이다', () => {
    expect(read('q=a&type=lives').type).toBe('lives')
    expect(read('q=a&type=members').type).toBe('all')
    expect(read('q=a&type=all').type).toBe('all')
  })

  it('page는 종류별 목록에서만, 0 이상의 정수만 읽는다', () => {
    expect(read('q=a&type=channels&page=3').page).toBe(3)
    expect(read('q=a&type=channels&page=-1').page).toBe(0)
    expect(read('q=a&type=channels&page=1.5').page).toBe(0)
    expect(read('q=a&type=channels&page=x').page).toBe(0)
    expect(read('q=a&page=3').page).toBe(0)
  })
})
