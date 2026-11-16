import type { SearchType } from '../../api/types'

/** 서버 규칙(§4.16)과 같은 검색어 길이 상한 */
export const SEARCH_MAX_LENGTH = 50

export const SEARCH_TYPES: readonly SearchType[] = ['channels', 'lives', 'recordings']

/** 앞뒤 공백을 떼고 연속 공백을 한 칸으로 줄이고 50자(글자 단위)까지만 남긴다. 빈 값이면 '' */
export function normalizeQuery(raw: string): string {
  const text = raw.trim().replace(/\s+/g, ' ')
  const chars = Array.from(text)
  return chars.length > SEARCH_MAX_LENGTH ? chars.slice(0, SEARCH_MAX_LENGTH).join('').trim() : text
}

/** `/search?q=…` 주소. 정리한 값이 비면 null(이동하지 않는다). type · page는 종류별 목록(더 보기)일 때만 붙는다 */
export function searchPath(raw: string, type?: SearchType, page = 0): string | null {
  const q = normalizeQuery(raw)
  if (q === '') return null
  let path = `/search?q=${encodeURIComponent(q)}`
  if (type) {
    path += `&type=${type}`
    if (page > 0) path += `&page=${page}`
  }
  return path
}

export type SearchParams = { q: string; type: SearchType | 'all'; page: number }

/** 주소의 쿼리에서 읽는다. 모르는 type은 all, page는 0 이상의 정수(아니면 0). type이 all이면 page는 쓰지 않는다 */
export function readSearchParams(params: URLSearchParams): SearchParams {
  const q = normalizeQuery(params.get('q') ?? '')
  const rawType = params.get('type')
  const type = SEARCH_TYPES.find((item) => item === rawType) ?? 'all'
  const rawPage = Number(params.get('page'))
  const page = type !== 'all' && Number.isInteger(rawPage) && rawPage > 0 ? rawPage : 0
  return { q, type, page }
}
