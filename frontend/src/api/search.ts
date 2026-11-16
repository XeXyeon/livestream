import { apiFetch, toQueryString } from './client'
import type { BroadcastSummary, ChannelSummary, Page, RecordingSummary, SearchResponse, SearchType } from './types'

/** 통합 검색 요약: 채널 · 라이브 · 다시보기 각 최대 5개 + 전체 수. q는 1~50자(호출 전에 searchQuery.ts로 정리한다) */
export const search = (q: string) => apiFetch<SearchResponse>(`/search${toQueryString({ q })}`)

type SearchItem = { channels: ChannelSummary; lives: BroadcastSummary; recordings: RecordingSummary }

/** 한 종류의 전체 목록(S12 "더 보기"). size는 서버 기본 20 */
export const searchPage = <T extends SearchType>(type: T, q: string, page = 0) =>
  apiFetch<Page<SearchItem[T]>>(`/search/${type}${toQueryString({ q, page })}`)
