import { useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { Radio } from 'lucide-react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Heading } from '@astryxdesign/core/Heading'
import { CATEGORIES } from '../api/types'
import type { CategoryCode } from '../api/types'
import { liveBroadcastsQuery, recentRecordingsQuery } from '../components/broadcast/listQueries'
import LiveCard from '../components/broadcast/LiveCard'
import RecordingCard from '../components/broadcast/RecordingCard'
import Spinner from '../components/common/Spinner'

const isCategory = (value: string | null): value is CategoryCode => CATEGORIES.some((category) => category.code === value)

function MoreButton({ onClick, loading }: { onClick: () => void; loading: boolean }) {
  return (
    <div className="mt-6 text-center">
      <Button label={loading ? '불러오는 중…' : '더 보기'} variant="secondary" isLoading={loading} onClick={onClick} />
    </div>
  )
}

/** 라이브 · 다시보기 모두 처음 최대 8개, "더 보기"마다 8개씩 더 보인다 */
const STEP = 8

/** 처음 상태에서는 화면 폭별로 두 줄만 보인다(1열 2개 · 2열 4개 · 4열 8개) */
function firstRowsClass(index: number): string {
  if (index < 2) return ''
  if (index < 4) return 'hidden sm:block'
  return 'hidden lg:block'
}

// S1 홈: 카테고리 칩 · 라이브 목록(시청자 수 순, 30초 갱신) · 다시보기 목록(처음엔 두 줄, 더 보기로 펼침)
export default function Home() {
  const [params, setParams] = useSearchParams()
  const raw = params.get('category')
  const category = isCategory(raw) ? raw : undefined

  const lives = useInfiniteQuery(liveBroadcastsQuery(category))
  const recordings = useInfiniteQuery(recentRecordingsQuery())
  const [recordingLimit, setRecordingLimit] = useState(STEP)
  const [liveLimit, setLiveLimit] = useState(STEP)
  // 카테고리를 바꾸면 라이브는 다시 처음 8개부터
  const [prevCategory, setPrevCategory] = useState(category)
  if (prevCategory !== category) {
    setPrevCategory(category)
    setLiveLimit(STEP)
  }

  // 8개를 더 보인다. 받아 둔 것이 모자라면 다음 쪽을 받는다
  function showMoreLives() {
    const next = liveLimit + STEP
    setLiveLimit(next)
    if (liveItems.length < next && lives.hasNextPage && !lives.isFetchingNextPage) void lives.fetchNextPage()
  }

  // 8개를 더 보인다. 받아 둔 것이 모자라면 다음 쪽을 받는다
  function showMoreRecordings() {
    const next = recordingLimit + STEP
    setRecordingLimit(next)
    if (recordingItems.length < next && recordings.hasNextPage && !recordings.isFetchingNextPage) void recordings.fetchNextPage()
  }

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
              : liveItems.length === 0 ? <div className="rounded-xl bg-surface py-6"><EmptyState title="지금 방송 중인 채널이 없습니다." icon={<Radio aria-hidden className="h-8 w-8" />} /></div>
                : (
                  <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
                    {liveItems.slice(0, liveLimit).map((broadcast) => <LiveCard key={broadcast.id} broadcast={broadcast} />)}
                  </div>
                )}
          {(liveItems.length > liveLimit || lives.hasNextPage) && <MoreButton onClick={showMoreLives} loading={lives.isFetchingNextPage} />}
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
                    {recordingItems.slice(0, recordingLimit).map((recording, index) => (
                      <div key={recording.id} className={recordingLimit === STEP ? firstRowsClass(index) : ''}>
                        <RecordingCard recording={recording} />
                      </div>
                    ))}
                  </div>
                )}
          {(recordingItems.length > recordingLimit || recordings.hasNextPage || (recordingLimit === STEP && recordingItems.length > 2)) && (
            <MoreButton onClick={showMoreRecordings} loading={recordings.isFetchingNextPage} />
          )}
        </div>
      </section>
    </div>
  )
}
