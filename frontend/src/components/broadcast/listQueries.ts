import { infiniteQueryOptions } from '@tanstack/react-query'
import { listBroadcasts } from '../../api/broadcasts'
import { listRecordings } from '../../api/recordings'
import type { CategoryCode, Page } from '../../api/types'

const nextPage = <T,>(last: Page<T>) => (last.hasNext ? last.page + 1 : undefined)

/**
 * 홈(S1)과 채널 사이드바가 함께 쓰는 목록 쿼리. 같은 키 · 같은 옵션이라 요청 하나를 나눠 쓴다
 * (사이드바는 첫 페이지만 본다).
 */
export const liveBroadcastsQuery = (category?: CategoryCode) =>
  infiniteQueryOptions({
    queryKey: ['broadcasts', 'LIVE', category ?? 'ALL'],
    queryFn: ({ pageParam }) => listBroadcasts({ status: 'LIVE', category, page: pageParam }),
    initialPageParam: 0,
    getNextPageParam: nextPage,
    refetchInterval: 30_000,
  })

export const recentRecordingsQuery = () =>
  infiniteQueryOptions({
    queryKey: ['recordings', 'recent'],
    queryFn: ({ pageParam }) => listRecordings(pageParam),
    initialPageParam: 0,
    getNextPageParam: nextPage,
  })
