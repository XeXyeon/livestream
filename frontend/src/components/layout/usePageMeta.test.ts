import { describe, expect, it } from 'vitest'
import { DEFAULT_TITLE, canonicalUrl, pageTitle } from './usePageMeta'

describe('pageTitle', () => {
  it('이름이 있으면 "<이름> · 그리드라이브"', () => {
    expect(pageTitle('야식 만들기 라이브')).toBe('야식 만들기 라이브 · 그리드라이브')
    expect(pageTitle('  로그인 ')).toBe('로그인 · 그리드라이브')
  })
  it('이름이 없거나 비면 기본 제목', () => {
    expect(pageTitle()).toBe(DEFAULT_TITLE)
    expect(pageTitle(null)).toBe(DEFAULT_TITLE)
    expect(pageTitle('   ')).toBe(DEFAULT_TITLE)
  })
})

describe('canonicalUrl', () => {
  it('origin + 경로(쿼리 없이)', () => {
    expect(canonicalUrl('https://example.com', '/channel/22')).toBe('https://example.com/channel/22')
  })
})
