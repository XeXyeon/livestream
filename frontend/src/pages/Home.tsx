import { useInfiniteQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
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
      <button type="button" onClick={onClick} disabled={loading} className="rounded-lg border border-slate-700 px-4 py-2 text-sm text-slate-200 hover:border-slate-500 disabled:opacity-50">
        {loading ? '불러오는 중…' : '더 보기'}
      </button>
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
  const chip = (active: boolean) => `rounded-full px-3.5 py-1.5 text-sm font-medium ${active ? 'bg-indigo-500 text-white' : 'bg-slate-800 text-slate-300 hover:bg-slate-700'}`

  return (
    <div className="space-y-12">
      <section>
        <h1 className="text-2xl font-bold">지금 라이브</h1>
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
            : lives.isError ? <p role="alert" className="text-sm text-rose-300">라이브 목록을 불러오지 못했습니다.</p>
              : liveItems.length === 0 ? <p className="rounded-xl border border-dashed border-slate-700 py-12 text-center text-slate-400">지금 방송 중인 채널이 없습니다.</p>
                : (
                  <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
                    {liveItems.map((broadcast) => <LiveCard key={broadcast.id} broadcast={broadcast} />)}
                  </div>
                )}
          {lives.hasNextPage && <MoreButton onClick={() => void lives.fetchNextPage()} loading={lives.isFetchingNextPage} />}
        </div>
      </section>

      <section>
        <h2 className="text-xl font-bold">다시보기</h2>
        <div className="mt-4">
          {recordings.isPending ? <Spinner />
            : recordings.isError ? <p role="alert" className="text-sm text-rose-300">다시보기 목록을 불러오지 못했습니다.</p>
              : recordingItems.length === 0 ? <p className="text-sm text-slate-400">아직 공개된 다시보기가 없습니다.</p>
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
