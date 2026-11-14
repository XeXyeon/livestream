import { useInfiniteQuery } from '@tanstack/react-query'
import { liveBroadcastsQuery, recentRecordingsQuery } from '../broadcast/listQueries'
import { buildSidebarChannels } from './sidebarChannels'

/**
 * 사이드바 채널 목록. 홈과 같은 쿼리(키 · 옵션)를 써서 홈에서는 요청이 겹치지 않는다. 첫 페이지(20개)만 본다.
 * 묶음마다 상태를 따로 돌려준다.
 */
export function useSidebarChannels() {
  const lives = useInfiniteQuery(liveBroadcastsQuery())
  const recent = useInfiniteQuery(recentRecordingsQuery())
  const channels = buildSidebarChannels(lives.data?.pages[0]?.items ?? [], recent.data?.pages[0]?.items ?? [])
  return {
    live: { channels: channels.live, isPending: lives.isPending, isError: lives.isError && !lives.data },
    recommended: { channels: channels.recommended, isPending: recent.isPending, isError: recent.isError && !recent.data },
  }
}
