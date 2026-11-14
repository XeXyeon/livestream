import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useLocation, useParams } from 'react-router-dom'
import { Users } from 'lucide-react'
import { Badge } from '@astryxdesign/core/Badge'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'
import { getBroadcast } from '../api/broadcasts'
import { getChannel, listChannelRecordings } from '../api/channels'
import { ApiError } from '../api/client'
import type { BroadcastDetail, ChannelResponse } from '../api/types'
import RecordingCard from '../components/broadcast/RecordingCard'
import { formatCount, formatRelative } from '../components/broadcast/format'
import ChatPanel from '../components/chat/ChatPanel'
import { useBroadcastChat } from '../components/chat/useBroadcastChat'
import Spinner from '../components/common/Spinner'
import Player from '../components/player/Player'
import DonateDialog from '../components/donation/DonateDialog'
import DonationBanner from '../components/donation/DonationBanner'
import { useDonationBanner } from '../components/donation/useDonationBanner'
import { useDonationReturn } from '../components/donation/useDonationReturn'
import type { DonationReturnState } from '../components/donation/useDonationReturn'
import FollowButton from '../components/follow/FollowButton'
import { useAuth } from '../store/auth'

// S2 시청. 채널 → liveBroadcastId → 방송 상세. 방송이 끝나 liveBroadcastId가 null이 돼도
// 마지막으로 본 방송을 그대로 보여 줘 종료 화면이 유지된다. 상태 · 시청자 수는 STOMP status · viewers가
// 방송 상세 캐시를 바로 고치고(useBroadcastChat), 폴링은 30초 백업이다(FE-5).
// 후원(FE-6): 후원 버튼 → DonateDialog → Toss 결제 → 이 화면으로 돌아와 confirm(useDonationReturn) · 실시간 후원은 플레이어 위 배너.
export default function Live() {
  const channelIdParam = useParams().channelId
  const donationReturn = useDonationReturn()
  return (
    <div className="space-y-4">
      <DonationReturnNotice {...donationReturn} />
      {/* 다른 채널로 옮기면 배너 큐 · 후원 창을 새로 만든다(이전 채널의 상태와 복귀 주소를 남기지 않음) */}
      <LiveMain key={channelIdParam} />
    </div>
  )
}

function LiveMain() {
  const channelId = Number(useParams().channelId)
  const validId = Number.isInteger(channelId) && channelId > 0

  const channel = useQuery({
    queryKey: ['channel', channelId],
    queryFn: () => getChannel(channelId),
    enabled: validId,
    refetchInterval: 30_000,
  })

  const liveId = channel.data?.liveBroadcastId ?? null
  const [lastSeen, setLastSeen] = useState<{ channelId: number; broadcastId: number } | null>(null)
  if (liveId !== null && (lastSeen?.channelId !== channelId || lastSeen.broadcastId !== liveId)) {
    setLastSeen({ channelId, broadcastId: liveId })
  }
  const broadcastId = liveId ?? (lastSeen?.channelId === channelId ? lastSeen.broadcastId : null)

  const broadcast = useQuery({
    queryKey: ['broadcast', broadcastId],
    queryFn: () => getBroadcast(broadcastId!),
    enabled: broadcastId !== null,
    // ENDED 뒤에도 녹화 병합이 끝날 때까지(RECORDING · PROCESSING) 15초마다 다시 봐서 다시보기 버튼을 켠다.
    refetchInterval: (query) => {
      const data = query.state.data
      if (data?.status !== 'ENDED') return 30_000 // STOMP가 끊겼을 때의 백업
      const pending = data.recording?.status === 'RECORDING' || data.recording?.status === 'PROCESSING'
      return pending ? 15_000 : false
    },
  })
  const broadcastGone = broadcast.error instanceof ApiError && broadcast.error.status === 404
  // 없는 방송(404)의 토픽에는 붙지 않는다
  const banner = useDonationBanner()
  const chat = useBroadcastChat(broadcastGone ? null : broadcastId, { onDonation: banner.push })
  const [donating, setDonating] = useState(false)

  if (!validId || (channel.error instanceof ApiError && channel.error.status === 404)) {
    return <p className="py-20 text-center text-secondary">채널을 찾을 수 없습니다.</p>
  }
  if (channel.isPending) return <Spinner />
  if (channel.isError) return <div className="py-20"><Banner role="alert" status="error" title="채널 정보를 불러오지 못했습니다." /></div>

  if (broadcastId === null || broadcastGone) return <OfflineView channel={channel.data} />
  if (broadcast.isPending) return <Spinner label="방송을 불러오는 중" />
  if (broadcast.isError) return <div className="py-20"><Banner role="alert" status="error" title="방송 정보를 불러오지 못했습니다." /></div>

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="min-w-0 space-y-4">
        <div className="relative">
          <Player key={broadcast.data.id} broadcast={broadcast.data} initialMode={broadcast.data.recommendedMode} />
          <div className="pointer-events-none absolute inset-x-3 top-3 z-10 flex justify-center">
            <div className="w-full max-w-md"><DonationBanner donation={banner.current} overlay /></div>
          </div>
        </div>
        <BroadcastInfo broadcast={broadcast.data} onDonate={() => setDonating(true)} />
        <DonateDialog broadcastId={broadcast.data.id} channelId={channelId} isOpen={donating} onOpenChange={setDonating} />
      </div>
      {/* 모바일은 플레이어 아래 */}
      <ChatPanel chat={chat} ended={broadcast.data.status === 'ENDED'} className="h-[28rem] lg:h-[calc(100vh-8rem)] lg:max-h-[48rem]" />
    </div>
  )
}

function BroadcastInfo({ broadcast, onDonate }: { broadcast: BroadcastDetail; onDonate: () => void }) {
  return (
    <div>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <Heading level={1}>{broadcast.title}</Heading>
        {broadcast.status !== 'ENDED' && !broadcast.isOwner && <DonateButton onDonate={onDonate} />}
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-secondary">
        <Link to={`/channel/${broadcast.channel.id}`} className="font-semibold text-primary hover:text-accent">{broadcast.channel.name}</Link>
        <FollowButton channelId={broadcast.channel.id} isFollowing={broadcast.isFollowing} followerCount={broadcast.followerCount} isOwner={broadcast.isOwner} />
        {broadcast.status !== 'ENDED' && (
          <span className="flex items-center gap-1"><Users aria-hidden className="h-4 w-4" />시청자 {formatCount(broadcast.viewerCount)}</span>
        )}
        <Badge variant="neutral" label={broadcast.category.label} />
        <span>{formatRelative(broadcast.startedAt)} 시작</span>
      </div>
    </div>
  )
}

// 비로그인은 로그인으로 보낸 뒤 이 화면으로 돌아온다
function DonateButton({ onDonate }: { onDonate: () => void }) {
  const loggedIn = useAuth((state) => state.member !== null)
  const authReady = useAuth((state) => state.ready)
  const location = useLocation()
  if (!authReady) return null
  return loggedIn
    ? <Button label="후원하기" variant="primary" onClick={onDonate} />
    : <Button label="로그인하고 후원하기" variant="secondary" href={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} />
}

// 결제에서 돌아온 결과. 확인 중에는 닫을 수 없다(useDonationReturn)
function DonationReturnNotice({ state, retry, dismiss }: { state: DonationReturnState; retry: () => void; dismiss: () => void }) {
  switch (state.status) {
    case 'idle':
      return null
    case 'confirming':
      return <Banner role="status" status="info" title="후원 결제를 확인하는 중입니다…" />
    case 'approved':
      return (
        <Banner
          role="status"
          status="success"
          title={`${state.donation.amount.toLocaleString('ko-KR')}원을 후원했습니다.`}
          description="채팅과 방송 화면에 표시됩니다."
          isDismissable
          onDismiss={dismiss}
        />
      )
    case 'canceled':
      return <Banner role="status" status="info" title="결제를 취소했습니다." isDismissable onDismiss={dismiss} />
    case 'needsLogin':
      return <DonationLoginNotice />
    case 'failed':
      return (
        <Banner
          role="alert"
          status="error"
          title={state.message}
          description={state.canRetry ? '결제가 이미 됐을 수 있습니다. 다시 확인을 눌러 주세요.' : undefined}
          endContent={state.canRetry ? <Button label="다시 확인" variant="secondary" size="sm" onClick={retry} /> : undefined}
          isDismissable
          onDismiss={dismiss}
        />
      )
  }
}

// 로그아웃 상태로 결제에서 돌아왔을 때: 로그인한 뒤 이 화면으로 돌아오면 이어서 확인한다
function DonationLoginNotice() {
  const location = useLocation()
  return (
    <Banner
      role="alert"
      status="warning"
      title="로그인이 풀려 후원 결제를 확인하지 못했습니다."
      description="다시 로그인하면 이어서 확인합니다."
      endContent={<Button label="로그인" variant="secondary" size="sm" href={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} />}
    />
  )
}

// liveBroadcastId가 없을 때: 채널 소개 + 최근 공개 다시보기 4개. 채널 쿼리가 30초마다 다시 돌아 라이브가 시작되면 플레이어로 바뀐다.
function OfflineView({ channel }: { channel: ChannelResponse }) {
  const recordings = useQuery({
    queryKey: ['channel', channel.id, 'recordings', 'recent4'],
    queryFn: () => listChannelRecordings(channel.id, 0, 4),
  })
  return (
    <div className="space-y-8">
      <section className="rounded-lg border border-border bg-surface p-8 text-center">
        <Heading level={1}>{channel.name}</Heading>
        {channel.description && <p className="mx-auto mt-2 max-w-xl whitespace-pre-line text-secondary">{channel.description}</p>}
        <p className="mt-6 font-semibold text-primary">지금은 방송 중이 아닙니다</p>
        <div className="mt-4"><Button href={`/channel/${channel.id}`} label="채널 페이지로 가기" variant="secondary" /></div>
      </section>
      {recordings.data && recordings.data.items.length > 0 && (
        <section>
          <div className="mb-3"><Heading level={2}>최근 다시보기</Heading></div>
          <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {recordings.data.items.map((recording) => <RecordingCard key={recording.id} recording={recording} />)}
          </div>
        </section>
      )}
    </div>
  )
}
