import { useState } from 'react'
import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { MessageSquare, Users } from 'lucide-react'
import { Badge } from '@astryxdesign/core/Badge'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'
import { IconButton } from '@astryxdesign/core/IconButton'
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
import ClipStrip from '../components/clip/ClipStrip'
import { LiveClipNotice, useLiveClip } from '../components/clip/LiveClipButton'
import FollowButton from '../components/follow/FollowButton'
import { useAuth } from '../store/auth'
import { useAuthDialog } from '../store/authDialog'
import { usePlayerUi } from '../store/playerUi'
import { useIsFullscreen, useMediaQuery } from '../components/common/useMediaQuery'
import { usePageMeta } from '../components/layout/usePageMeta'

// S2 시청. 채널 → liveBroadcastId → 방송 상세. 방송이 끝나 liveBroadcastId가 null이 돼도
// 마지막으로 본 방송을 그대로 보여 줘 종료 화면이 유지된다. 상태 · 시청자 수는 STOMP status · viewers가
// 방송 상세 캐시를 바로 고치고(useBroadcastChat), 폴링은 30초 백업이다(FE-5).
// 후원(FE-6): 후원 버튼 → DonateDialog → Toss 결제 → 이 화면으로 돌아와 confirm(useDonationReturn) · 실시간 후원은 플레이어 위 배너.
export default function Live() {
  const channelIdParam = useParams().channelId
  const donationReturn = useDonationReturn()
  // 본문 여백은 이 화면이 정한다(Header의 fullBleed): 채팅을 오른쪽 끝까지 붙이기 위해
  return (
    <div>
      <div className="px-4 pt-6 empty:hidden sm:px-6"><DonationReturnNotice {...donationReturn} /></div>
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
  const channelGone = !validId || (channel.error instanceof ApiError && channel.error.status === 404)
  // 방송 중이면 방송 제목, 아니면 채널 이름
  usePageMeta({ title: broadcast.data?.title ?? channel.data?.name ?? null, noindex: channelGone })
  // 없는 방송(404)의 토픽에는 붙지 않는다
  const banner = useDonationBanner()
  const chat = useBroadcastChat(broadcastGone ? null : broadcastId, { onDonation: banner.push })
  const [donating, setDonating] = useState(false)
  const [chatCollapsed, setChatCollapsed] = useState(readChatCollapsed)
  const theater = usePlayerUi((state) => state.theater)
  const setTheater = usePlayerUi((state) => state.setTheater)
  const wide = useMediaQuery('(min-width: 64rem)')
  const theaterOn = theater && wide
  const fullscreen = useIsFullscreen()
  // 채팅을 새 창으로 띄우면 이 화면의 채팅은 접는다(넓은 화면). 창 이름이 채널별이라 다시 누르면 같은 창을 쓴다
  const popoutChat = () => {
    const popup = window.open(`/live/${channelId}/chat`, `gridlive-chat-${channelId}`, 'popup,width=380,height=720')
    if (popup) changeChatCollapsed(true)
  }
  const changeChatCollapsed = (next: boolean) => {
    setChatCollapsed(next)
    writeChatCollapsed(next)
  }
  // 클립(CLIP-2 · FE-12): 가위 버튼은 플레이어 컨트롤 줄에, 다이얼로그 · 오류 · 진행 알림은 방송 정보 아래에 둔다
  const [clipId, setClipId] = useState<number | null>(null)
  const clip = useLiveClip(broadcast.data ?? null, setClipId)

  if (!validId || (channel.error instanceof ApiError && channel.error.status === 404)) {
    return <Padded><p className="py-20 text-center text-secondary">채널을 찾을 수 없습니다.</p></Padded>
  }
  if (channel.isPending) return <Padded><Spinner /></Padded>
  if (channel.isError) return <Padded><div className="py-20"><Banner role="alert" status="error" title="채널 정보를 불러오지 못했습니다." /></div></Padded>

  if (broadcastId === null || broadcastGone) return <Padded><OfflineView channel={channel.data} /></Padded>
  if (broadcast.isPending) return <Padded><Spinner label="방송을 불러오는 중" /></Padded>
  if (broadcast.isError) return <Padded><div className="py-20"><Banner role="alert" status="error" title="방송 정보를 불러오지 못했습니다." /></div></Padded>

  // lg 이상: 채팅이 오른쪽 끝에 헤더 아래 전체 높이로 붙고 접을 수 있다. 그보다 좁으면 플레이어 아래 카드.
  // 영화관 모드(lg 이상): 영상 칸이 헤더 아래 높이를 다 채우고(검은 바탕), 방송 정보는 그 아래로 스크롤해 본다.
  // data-player-root: 플레이어의 전체 화면 버튼이 이 칸(후원 배너 포함)을 띄운다.
  return (
    <div className="lg:flex lg:items-start">
      <div className="min-w-0 flex-1">
        <div className={theaterOn ? '' : 'px-4 pt-6 sm:px-6'}>
          <div data-player-root className={`relative ${theaterOn ? 'h-[calc(100dvh-3.5rem)] bg-black' : ''}`}>
            <Player
              key={broadcast.data.id}
              broadcast={broadcast.data}
              initialMode={broadcast.data.recommendedMode}
              theater={theaterOn}
              onTheaterChange={setTheater}
              actions={clip.action ? [clip.action] : undefined}
              // 길면 한 줄에서 말줄임(…), 잘리면 툴팁으로 전체 제목
              title={<Heading level={1} maxLines={1}>{broadcast.data.title}</Heading>}
            />
            <div className="pointer-events-none absolute inset-x-3 top-3 z-20 flex justify-center">
              <div className="w-full max-w-md"><DonationBanner donation={banner.current} overlay /></div>
            </div>
            {chatCollapsed && !fullscreen && (
              <div className="absolute right-3 top-3 z-20 hidden lg:block">
                <IconButton label="채팅 펼치기" icon={<MessageSquare aria-hidden className="h-4 w-4" />} variant="secondary" size="sm" onClick={() => changeChatCollapsed(false)} />
              </div>
            )}
          </div>
        </div>
        <div className="space-y-4 px-4 pb-4 pt-2 sm:px-6">
          <BroadcastInfo broadcast={broadcast.data} clipId={clipId} onClipDismiss={() => setClipId(null)} clipElement={clip.element} />
          <DonateDialog broadcastId={broadcast.data.id} channelId={channelId} isOpen={donating} onOpenChange={setDonating} />
        </div>
      </div>
      <ChatPanel
        chat={chat}
        ended={broadcast.data.status === 'ENDED'}
        framed={false}
        onCollapse={() => changeChatCollapsed(true)}
        onPopout={popoutChat}
        actions={broadcast.data.status !== 'ENDED' && !broadcast.data.isOwner ? <DonateButton onDonate={() => setDonating(true)} /> : undefined}
        // 사이드바와 같은 결: 넓은 화면에서는 선 없이 본문 바탕에 붙는다. 좁은 화면은 플레이어 아래 둥근 칸
        className={`mx-4 mb-6 h-[28rem] rounded-lg bg-surface sm:mx-6 lg:sticky lg:top-14 lg:m-0 lg:h-[calc(100dvh-3.5rem)] lg:w-[340px] lg:shrink-0 lg:rounded-none lg:bg-body ${chatCollapsed ? 'lg:hidden' : ''}`}
      />
    </div>
  )
}

function Padded({ children }: { children: ReactNode }) {
  return <div className="mx-auto max-w-6xl px-4 py-8 sm:px-5 sm:py-10">{children}</div>
}

// 채팅 접힘(lg 이상)은 브라우저에 기억한다. 저장소를 못 쓰면(사생활 보호 모드 등) 펼친 상태
const CHAT_COLLAPSED_KEY = 'gridlive.chat.collapsed'
function readChatCollapsed(): boolean {
  try { return localStorage.getItem(CHAT_COLLAPSED_KEY) === '1' } catch { return false }
}
function writeChatCollapsed(collapsed: boolean) {
  try { localStorage.setItem(CHAT_COLLAPSED_KEY, collapsed ? '1' : '0') } catch { /* 기억하지 못해도 화면은 동작 */ }
}

function BroadcastInfo({ broadcast, clipId, onClipDismiss, clipElement }: {
  broadcast: BroadcastDetail
  clipId: number | null
  onClipDismiss: () => void
  clipElement: ReactNode
}) {
  return (
    <div className="space-y-3">
      {/* 방송 중에는 제목이 플레이어 바로 아래 줄에 있다. 종료 화면에는 그 줄이 없어 여기서 보인다 */}
      {broadcast.status === 'ENDED' && <div className="mb-2 min-w-0"><Heading level={1} maxLines={1}>{broadcast.title}</Heading></div>}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-secondary">
        <Link to={`/channel/${broadcast.channel.id}`} className="font-semibold text-primary hover:text-accent">{broadcast.channel.name}</Link>
        <FollowButton channelId={broadcast.channel.id} isFollowing={broadcast.isFollowing} followerCount={broadcast.followerCount} isOwner={broadcast.isOwner} />
        {broadcast.status !== 'ENDED' && (
          <span className="flex items-center gap-1"><Users aria-hidden className="h-4 w-4" />시청자 {formatCount(broadcast.viewerCount)}</span>
        )}
        <Badge variant="neutral" label={broadcast.category.label} />
        <span>{formatRelative(broadcast.startedAt)} 시작</span>
      </div>
      {/* 클립(CLIP-2): 플레이어의 가위 버튼 → 만들기 창 · 오류 · 한 줄 알림(만드는 중 → 클립 보기) */}
      {clipElement}
      <LiveClipNotice clipId={clipId} onDismiss={onClipDismiss} />
      <div className="pt-3"><ClipStrip channelId={broadcast.channel.id} /></div>
    </div>
  )
}

// 채팅 입력창 아래 왼쪽. 채팅 보내기와 같이 모양은 하나이고, 비로그인이 누르면 로그인 모달을 띄운다
function DonateButton({ onDonate }: { onDonate: () => void }) {
  const loggedIn = useAuth((state) => state.member !== null)
  const authReady = useAuth((state) => state.ready)
  const openLogin = useAuthDialog((state) => state.open)
  if (!authReady) return null
  return <Button label="후원하기" variant="secondary" size="sm" onClick={() => (loggedIn ? onDonate() : openLogin())} />
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

// 로그아웃 상태로 결제에서 돌아왔을 때: 모달로 로그인하면 이어서 확인한다(useDonationReturn이 로그인 상태를 본다)
function DonationLoginNotice() {
  const openLogin = useAuthDialog((state) => state.open)
  return (
    <Banner
      role="alert"
      status="warning"
      title="로그인이 풀려 후원 결제를 확인하지 못했습니다."
      description="다시 로그인하면 이어서 확인합니다."
      endContent={<Button label="로그인" variant="secondary" size="sm" onClick={() => openLogin()} />}
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
