import { useQuery } from '@tanstack/react-query'
import { listBroadcasts } from '../../api/broadcasts'
import { listRecordings } from '../../api/recordings'
import { buildSidebarChannels } from './sidebarChannels'

/** 사이드바 채널 목록(지금 라이브 30초마다 · 추천은 최근 다시보기). 묶음마다 상태를 따로 돌려준다 */
export function useSidebarChannels() {
  const lives = useQuery({
    queryKey: ['sidebar', 'live'],
    queryFn: () => listBroadcasts({ status: 'LIVE', size: 20 }),
    refetchInterval: 30_000,
  })
  const recent = useQuery({
    queryKey: ['sidebar', 'recent'],
    queryFn: () => listRecordings(0, 20),
    staleTime: 60_000,
  })
  const channels = buildSidebarChannels(lives.data?.items ?? [], recent.data?.items ?? [])
  return {
    live: { channels: channels.live, isPending: lives.isPending, isError: lives.isError && !lives.data },
    recommended: { channels: channels.recommended, isPending: recent.isPending, isError: recent.isError && !recent.data },
  }
}
