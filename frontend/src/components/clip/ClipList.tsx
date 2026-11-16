import { useInfiniteQuery } from '@tanstack/react-query'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Heading } from '@astryxdesign/core/Heading'
import { Scissors } from 'lucide-react'
import { listChannelClips, listRecordingClips } from '../../api/clips'
import Spinner from '../common/Spinner'
import ClipCard from './ClipCard'

type Source = { kind: 'channel'; id: number } | { kind: 'recording'; id: number }

/**
 * 클립 그리드(§6.7). 채널 "클립" 탭은 빈 안내를 보이고, 다시보기 아래 "이 영상의 클립"은 없으면 절째 숨긴다(heading을 줄 때).
 */
export default function ClipList({ source, heading }: { source: Source; heading?: string }) {
  const size = source.kind === 'channel' ? 12 : 8
  const query = useInfiniteQuery({
    queryKey: ['clips', source.kind, source.id, size],
    queryFn: ({ pageParam }) => (source.kind === 'channel' ? listChannelClips(source.id, pageParam, size) : listRecordingClips(source.id, pageParam, size)),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
  })
  const items = query.data?.pages.flatMap((page) => page.items) ?? []
  const headingId = `clip-list-${source.kind}-${source.id}`

  if (heading && (query.isPending || query.isError || items.length === 0)) return null
  if (query.isPending) return <Spinner label="클립을 불러오는 중" />
  if (query.isError) return <Banner role="alert" status="error" title="클립을 불러오지 못했습니다." />
  if (items.length === 0) {
    return <div className="rounded-lg border border-dashed border-border py-6"><EmptyState title="아직 클립이 없습니다." icon={<Scissors aria-hidden className="h-8 w-8" />} /></div>
  }

  const grid = (
    <div className="space-y-4">
      <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
        {items.map((clip) => <li key={clip.id}><ClipCard clip={clip} /></li>)}
      </ul>
      {query.hasNextPage && (
        <div className="flex justify-center">
          <Button label="더 보기" variant="secondary" isLoading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
        </div>
      )}
    </div>
  )
  if (!heading) return grid
  return (
    <section aria-labelledby={headingId} className="space-y-3">
      <Heading level={2} id={headingId}>{heading}</Heading>
      {grid}
    </section>
  )
}
