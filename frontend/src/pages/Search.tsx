import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { SearchX } from 'lucide-react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Heading } from '@astryxdesign/core/Heading'
import { search, searchPage } from '../api/search'
import type { BroadcastSummary, ChannelSummary, RecordingSummary, SearchType } from '../api/types'
import LiveCard from '../components/broadcast/LiveCard'
import RecordingCard from '../components/broadcast/RecordingCard'
import Spinner from '../components/common/Spinner'
import ChannelCard from '../components/search/ChannelCard'
import SearchSection from '../components/search/SearchSection'
import { readSearchParams, searchPath } from '../components/search/searchQuery'
import { usePageMeta } from '../components/layout/usePageMeta'

const STALE_TIME = 30_000
const CARD_GRID = 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-4'
const CHANNEL_GRID = 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-3'
const TYPE_TITLES: Record<SearchType, string> = { channels: '채널', lives: '라이브', recordings: '다시보기' }

// S12 검색 결과: ?q=(필수) · ?type=all|channels|lives|recordings · ?page=(종류일 때).
// all이면 GET /search 한 번으로 세 절(각 최대 5개 + 전체 수), 종류면 GET /search/{type}로 그 종류만 이전 · 다음 쪽.
export default function Search() {
  const [params] = useSearchParams()
  const { q, type, page } = readSearchParams(params)
  usePageMeta({ title: q === '' ? '검색' : `'${q}' 검색 결과` })

  if (q === '') {
    return (
      <div className="rounded-xl bg-surface py-6">
        <EmptyState headingLevel={1} title="검색어를 입력해 주세요" description="채널 · 라이브 · 다시보기를 한 번에 찾아 줍니다." icon={<SearchX aria-hidden className="h-8 w-8" />} />
      </div>
    )
  }
  return type === 'all' ? <SearchSummary q={q} /> : <SearchList q={q} type={type} page={page} />
}

function SearchSummary({ q }: { q: string }) {
  const query = useQuery({ queryKey: ['search', 'all', q, 0], queryFn: () => search(q), staleTime: STALE_TIME })

  if (query.isPending) return <Spinner label="검색하는 중" />
  if (query.isError) return <Banner role="alert" status="error" title="검색 결과를 불러오지 못했습니다." />
  const { channels, lives, recordings } = query.data
  if (channels.total + lives.total + recordings.total === 0) {
    return (
      <div className="rounded-xl bg-surface py-6">
        <EmptyState headingLevel={1} title={`'${query.data.q}'에 맞는 채널 · 라이브 · 다시보기가 없습니다`} description="다른 검색어로 찾아 보세요." icon={<SearchX aria-hidden className="h-8 w-8" />} />
      </div>
    )
  }
  const more = (type: SearchType) => searchPath(q, type) ?? '/search'
  return (
    <div className="space-y-12">
      <Heading level={1}>‘{query.data.q}’ 검색 결과</Heading>
      <SearchSection title="채널" total={channels.total} shown={channels.items.length} moreHref={more('channels')} gridClassName={CHANNEL_GRID}>
        {channels.items.map((channel) => <ChannelCard key={channel.id} channel={channel} />)}
      </SearchSection>
      <SearchSection title="라이브" total={lives.total} shown={lives.items.length} moreHref={more('lives')} gridClassName={CARD_GRID}>
        {lives.items.map((broadcast) => <LiveCard key={broadcast.id} broadcast={broadcast} />)}
      </SearchSection>
      <SearchSection title="다시보기" total={recordings.total} shown={recordings.items.length} moreHref={more('recordings')} gridClassName={CARD_GRID}>
        {recordings.items.map((recording) => <RecordingCard key={recording.id} recording={recording} />)}
      </SearchSection>
    </div>
  )
}

function SearchList({ q, type, page }: { q: string; type: SearchType; page: number }) {
  const query = useQuery({
    queryKey: ['search', type, q, page],
    queryFn: () => searchPage(type, q, page),
    staleTime: STALE_TIME,
    placeholderData: keepPreviousData,
  })
  const title = TYPE_TITLES[type]

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Heading level={1}>
          ‘{q}’ {title} 검색 결과{' '}
          {query.data && <span className="text-base font-normal text-secondary">{query.data.totalElements.toLocaleString('ko-KR')}</span>}
        </Heading>
        <Button href={searchPath(q) ?? '/search'} label="전체 검색 결과" variant="ghost" size="sm" />
      </div>

      {query.isPending ? <Spinner label="검색하는 중" />
        : query.isError ? <Banner role="alert" status="error" title="검색 결과를 불러오지 못했습니다." />
          : query.data.items.length === 0 ? <p className="text-sm text-secondary">{title} 결과가 없습니다.</p>
            : (
              <div className={`grid gap-6 ${type === 'channels' ? CHANNEL_GRID : CARD_GRID}`}>
                {type === 'channels' && (query.data.items as ChannelSummary[]).map((channel) => <ChannelCard key={channel.id} channel={channel} />)}
                {type === 'lives' && (query.data.items as BroadcastSummary[]).map((broadcast) => <LiveCard key={broadcast.id} broadcast={broadcast} />)}
                {type === 'recordings' && (query.data.items as RecordingSummary[]).map((recording) => <RecordingCard key={recording.id} recording={recording} />)}
              </div>
            )}

      {query.data && (page > 0 || query.data.hasNext) && (
        <nav aria-label="쪽 이동" className="flex items-center justify-center gap-3">
          <Button href={searchPath(q, type, page - 1) ?? '/search'} label="이전" variant="secondary" size="sm" isDisabled={page === 0} />
          <span className="text-sm text-secondary">{page + 1}쪽</span>
          <Button href={searchPath(q, type, page + 1) ?? '/search'} label="다음" variant="secondary" size="sm" isDisabled={!query.data.hasNext} />
        </nav>
      )}
    </div>
  )
}
