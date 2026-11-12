import { useInfiniteQuery } from '@tanstack/react-query'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Video } from 'lucide-react'
import { listChannelRecordings } from '../../api/channels'
import RecordingCard from '../broadcast/RecordingCard'
import Spinner from '../common/Spinner'

/** 채널의 공개 다시보기(최신순). limit을 주면 그만큼만 보이고 더 보기를 숨긴다(홈 탭) */
export default function ChannelRecordings({ channelId, limit }: { channelId: number; limit?: number }) {
  const size = limit ?? 12
  const query = useInfiniteQuery({
    queryKey: ['channel', channelId, 'recordings', size],
    queryFn: ({ pageParam }) => listChannelRecordings(channelId, pageParam, size),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
  })
  const items = query.data?.pages.flatMap((page) => page.items) ?? []

  if (query.isPending) return <Spinner label="다시보기를 불러오는 중" />
  if (query.isError) return <Banner role="alert" status="error" title="다시보기를 불러오지 못했습니다." />
  if (items.length === 0) {
    return <div className="rounded-lg border border-dashed border-border py-6"><EmptyState title="공개된 다시보기가 없습니다." icon={<Video aria-hidden className="h-8 w-8" />} /></div>
  }
  return (
    <div className="space-y-4">
      <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
        {items.map((recording) => <li key={recording.id}><RecordingCard recording={recording} /></li>)}
      </ul>
      {!limit && query.hasNextPage && (
        <div className="flex justify-center">
          <Button label="더 보기" variant="secondary" isLoading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
        </div>
      )}
    </div>
  )
}
