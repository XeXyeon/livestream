import { useQuery } from '@tanstack/react-query'
import { useParams, useSearchParams } from 'react-router-dom'
import { Badge } from '@astryxdesign/core/Badge'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Heading } from '@astryxdesign/core/Heading'
import { Tab, TabList } from '@astryxdesign/core/TabList'
import { ApiError } from '../api/client'
import { getChannel } from '../api/channels'
import type { ChannelResponse } from '../api/types'
import ChannelPosts from '../components/channel/ChannelPosts'
import ChannelRecordings from '../components/channel/ChannelRecordings'
import Spinner from '../components/common/Spinner'
import { useAuth } from '../store/auth'

const TABS = [
  { value: 'home', label: '홈' },
  { value: 'recordings', label: '다시보기' },
  { value: 'posts', label: '커뮤니티' },
] as const
type TabValue = typeof TABS[number]['value']
const isTab = (value: string | null): value is TabValue => TABS.some((tab) => tab.value === value)

/** S9 채널 페이지(§6.3): 탭 홈 · 다시보기 · 커뮤니티. 탭은 `?tab=`에 둬 링크 · 뒤로 가기가 맞게 한다 */
export default function Channel() {
  const channelId = Number(useParams().channelId)
  const valid = Number.isInteger(channelId) && channelId > 0
  const [searchParams, setSearchParams] = useSearchParams()
  const requested = searchParams.get('tab')
  const tab: TabValue = isTab(requested) ? requested : 'home'
  const member = useAuth((state) => state.member)
  const query = useQuery({ queryKey: ['channel', channelId], queryFn: () => getChannel(channelId), enabled: valid, refetchInterval: 30_000 })

  if (!valid || (query.error instanceof ApiError && query.error.status === 404)) {
    return <EmptyState headingLevel={1} title="채널을 찾을 수 없습니다" description="없는 채널이거나 주소가 잘못되었습니다." />
  }
  if (query.isError) return <Banner role="alert" status="error" title="채널을 불러오지 못했습니다." description="잠시 뒤 다시 시도해 주세요." />
  if (!query.data) return <Spinner label="채널을 불러오는 중" />

  const channel = query.data
  const isOwner = member?.channelId === channel.id
  const selectTab = (value: string) => setSearchParams(value === 'home' ? {} : { tab: value }, { replace: true })

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <Heading level={1}>{channel.name}</Heading>
            {channel.liveBroadcastId != null && <Badge variant="error" label="LIVE" />}
          </div>
          <p className="text-sm text-secondary">{channel.owner.nickname} · {channel.category.label} · 다시보기 {channel.recordingCount.toLocaleString('ko-KR')} · 게시글 {channel.postCount.toLocaleString('ko-KR')}</p>
        </div>
        {isOwner && <Button href="/studio/settings" label="채널 설정" variant="secondary" size="sm" />}
      </div>

      <TabList value={tab} onChange={selectTab} hasDivider aria-label="채널 메뉴">
        {TABS.map((item) => <Tab key={item.value} value={item.value} label={item.label} panelId={`channel-panel-${item.value}`} />)}
      </TabList>

      <div id={`channel-panel-${tab}`} role="tabpanel" aria-label={TABS.find((item) => item.value === tab)?.label}>
        {tab === 'home' && <ChannelHome channel={channel} />}
        {tab === 'recordings' && <ChannelRecordings channelId={channel.id} />}
        {tab === 'posts' && <ChannelPosts channelId={channel.id} isOwner={isOwner} />}
      </div>
    </div>
  )
}

function ChannelHome({ channel }: { channel: ChannelResponse }) {
  return (
    <div className="space-y-6">
      {channel.liveBroadcastId != null && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-surface p-4">
          <p className="font-semibold text-primary">지금 방송 중입니다</p>
          <Button href={`/live/${channel.id}`} label="방송 보러 가기" variant="primary" />
        </div>
      )}
      <section aria-labelledby="channel-about" className="rounded-lg border border-border bg-surface p-5">
        <Heading level={2} id="channel-about">소개</Heading>
        <p className="mt-2 whitespace-pre-line break-words text-secondary">{channel.description || '아직 채널 소개가 없습니다.'}</p>
      </section>
      <section aria-labelledby="channel-recent" className="space-y-3">
        <Heading level={2} id="channel-recent">최근 다시보기</Heading>
        <ChannelRecordings channelId={channel.id} limit={4} />
      </section>
    </div>
  )
}
