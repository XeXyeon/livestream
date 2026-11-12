import { useInfiniteQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { Radio } from 'lucide-react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Heading } from '@astryxdesign/core/Heading'
import { listBroadcasts } from '../api/broadcasts'
import { listRecordings } from '../api/recordings'
import { CATEGORIES } from '../api/types'
import type { CategoryCode, Page } from '../api/types'
import LiveCard from '../components/broadcast/LiveCard'
import RecordingCard from '../components/broadcast/RecordingCard'
import Spinner from '../components/common/Spinner'

const nextPage = <T,>(last: Page<T>) => (last.hasNext ? last.page + 1 : undefined)
const isCategory = (value: string | null): value is CategoryCode => CATEGORIES.some((category) => category.code === value)

function MoreButton({ onClick, loading }: { onClick: () => void; loading: boolean }) {
  return (
    <div className="mt-6 text-center">
      <Button label={loading ? '불러오는 중…' : '더 보기'} variant="secondary" isLoading={loading} onClick={onClick} />
    </div>
  )
}

// S1 홈: 카테고리 칩 · 라이브 목록(시청자 수 순, 30초 갱신) · 다시보기 목록
export default function Home() {
  const [params, setParams] = useSearchParams()
  const raw = params.get('category')
  const category = isCategory(raw) ? raw : undefined

  const lives = useInfiniteQuery({
    queryKey: ['broadcasts', 'LIVE', category ?? 'ALL'],
    queryFn: ({ pageParam }) => listBroadcasts({ status: 'LIVE', category, page: pageParam }),
    initialPageParam: 0,
    getNextPageParam: nextPage,
    refetchInterval: 30_000,
  })
  const recordings = useInfiniteQuery({
    queryKey: ['recordings', 'recent'],
    queryFn: ({ pageParam }) => listRecordings(pageParam),
    initialPageParam: 0,
    getNextPageParam: nextPage,
  })

  function selectCategory(code: CategoryCode | undefined) {
    setParams(code ? { category: code } : {}, { replace: true })
  }

  const liveItems = lives.data?.pages.flatMap((page) => page.items) ?? []
  const recordingItems = recordings.data?.pages.flatMap((page) => page.items) ?? []
  const chip = (active: boolean) => `rounded-full px-3.5 py-1.5 text-sm font-medium ${active ? 'bg-accent-bg text-on-accent' : 'bg-surface text-secondary hover:bg-muted hover:text-primary'}`

  return (
    <div className="space-y-12">
      <section>
        <Heading level={1}>지금 라이브</Heading>
        <div role="group" aria-label="카테고리" className="mt-4 flex flex-wrap gap-2">
          <button type="button" aria-pressed={!category} onClick={() => selectCategory(undefined)} className={chip(!category)}>전체</button>
          {CATEGORIES.map((item) => (
            <button key={item.code} type="button" aria-pressed={category === item.code} onClick={() => selectCategory(item.code)} className={chip(category === item.code)}>
              {item.label}
            </button>
          ))}
        </div>
        <div className="mt-6">
          {lives.isPending ? <Spinner />
            : lives.isError ? <Banner role="alert" status="error" title="라이브 목록을 불러오지 못했습니다." />
              : liveItems.length === 0 ? <div className="rounded-lg border border-dashed border-border py-6"><EmptyState title="지금 방송 중인 채널이 없습니다." icon={<Radio aria-hidden className="h-8 w-8" />} /></div>
                : (
                  <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
                    {liveItems.map((broadcast) => <LiveCard key={broadcast.id} broadcast={broadcast} />)}
                  </div>
                )}
          {lives.hasNextPage && <MoreButton onClick={() => void lives.fetchNextPage()} loading={lives.isFetchingNextPage} />}
        </div>
      </section>

      <section>
        <Heading level={2}>다시보기</Heading>
        <div className="mt-4">
          {recordings.isPending ? <Spinner />
            : recordings.isError ? <Banner role="alert" status="error" title="다시보기 목록을 불러오지 못했습니다." />
              : recordingItems.length === 0 ? <p className="text-sm text-secondary">아직 공개된 다시보기가 없습니다.</p>
                : (
                  <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
                    {recordingItems.map((recording) => <RecordingCard key={recording.id} recording={recording} />)}
                  </div>
                )}
          {recordings.hasNextPage && <MoreButton onClick={() => void recordings.fetchNextPage()} loading={recordings.isFetchingNextPage} />}
        </div>
      </section>
    </div>
  )
}
