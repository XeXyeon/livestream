import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { Users } from 'lucide-react'
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

// S2 시청. 채널 → liveBroadcastId → 방송 상세. 방송이 끝나 liveBroadcastId가 null이 돼도
// 마지막으로 본 방송을 그대로 보여 줘 종료 화면이 유지된다. 상태 · 시청자 수는 STOMP status · viewers가
// 방송 상세 캐시를 바로 고치고(useBroadcastChat), 폴링은 30초 백업이다(FE-5). 후원 UI는 FE-6.
export default function Live() {
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
  const chat = useBroadcastChat(broadcastGone ? null : broadcastId)

  if (!validId || (channel.error instanceof ApiError && channel.error.status === 404)) {
    return <p className="py-20 text-center text-slate-400">채널을 찾을 수 없습니다.</p>
  }
  if (channel.isPending) return <Spinner />
  if (channel.isError) return <p role="alert" className="py-20 text-center text-rose-300">채널 정보를 불러오지 못했습니다.</p>

  if (broadcastId === null || broadcastGone) return <OfflineView channel={channel.data} />
  if (broadcast.isPending) return <Spinner label="방송을 불러오는 중" />
  if (broadcast.isError) return <p role="alert" className="py-20 text-center text-rose-300">방송 정보를 불러오지 못했습니다.</p>

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_320px]">
      <div className="min-w-0 space-y-4">
        <Player key={broadcast.data.id} broadcast={broadcast.data} initialMode={broadcast.data.recommendedMode} />
        <BroadcastInfo broadcast={broadcast.data} />
      </div>
      {/* 모바일은 플레이어 아래 */}
      <ChatPanel chat={chat} ended={broadcast.data.status === 'ENDED'} className="h-[28rem] lg:h-[calc(100vh-8rem)] lg:max-h-[48rem]" />
    </div>
  )
}

function BroadcastInfo({ broadcast }: { broadcast: BroadcastDetail }) {
  return (
    <div>
      <h1 className="text-xl font-bold text-white">{broadcast.title}</h1>
      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-slate-400">
        <Link to={`/channel/${broadcast.channel.id}`} className="font-semibold text-slate-200 hover:text-indigo-300">{broadcast.channel.name}</Link>
        {broadcast.status !== 'ENDED' && (
          <span className="flex items-center gap-1"><Users aria-hidden className="h-4 w-4" />시청자 {formatCount(broadcast.viewerCount)}</span>
        )}
        <span className="rounded bg-slate-800 px-2 py-0.5 text-xs text-slate-300">{broadcast.category.label}</span>
        <span>{formatRelative(broadcast.startedAt)} 시작</span>
      </div>
    </div>
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
      <section className="rounded-xl border border-slate-800 bg-slate-900 p-8 text-center">
        <h1 className="text-2xl font-bold text-white">{channel.name}</h1>
        {channel.description && <p className="mx-auto mt-2 max-w-xl whitespace-pre-line text-slate-400">{channel.description}</p>}
        <p className="mt-6 font-semibold text-slate-200">지금은 방송 중이 아닙니다</p>
        <Link to={`/channel/${channel.id}`} className="mt-4 inline-block text-sm font-medium text-indigo-300 hover:text-indigo-200">채널 페이지로 가기</Link>
      </section>
      {recordings.data && recordings.data.items.length > 0 && (
        <section>
          <h2 className="mb-3 text-lg font-bold">최근 다시보기</h2>
          <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
            {recordings.data.items.map((recording) => <RecordingCard key={recording.id} recording={recording} />)}
          </div>
        </section>
      )}
    </div>
  )
}
