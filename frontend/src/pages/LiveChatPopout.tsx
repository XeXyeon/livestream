import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { getBroadcast } from '../api/broadcasts'
import { getChannel } from '../api/channels'
import AuthDialog from '../components/auth/AuthDialog'
import ChatPanel from '../components/chat/ChatPanel'
import { useBroadcastChat } from '../components/chat/useBroadcastChat'
import Spinner from '../components/common/Spinner'
import { usePageMeta } from '../components/layout/usePageMeta'

/**
 * 시청 화면 채팅의 새 창(`/live/:channelId/chat`). 헤더 셸 밖이라 창 전체를 채팅이 채운다.
 * 채널 → 지금 방송 → 같은 채팅 토픽에 붙는다. 로그인은 이 창 안의 모달로 한다(같은 출처라 refresh 쿠키를 함께 쓴다).
 */
export default function LiveChatPopout() {
  const channelId = Number(useParams().channelId)
  const validId = Number.isInteger(channelId) && channelId > 0
  const channel = useQuery({ queryKey: ['channel', channelId], queryFn: () => getChannel(channelId), enabled: validId, refetchInterval: 30_000 })
  const broadcastId = channel.data?.liveBroadcastId ?? null
  const broadcast = useQuery({ queryKey: ['broadcast', broadcastId], queryFn: () => getBroadcast(broadcastId!), enabled: broadcastId !== null, refetchInterval: 30_000 })
  const chat = useBroadcastChat(broadcastId)
  usePageMeta({ title: channel.data ? `${channel.data.name} 채팅` : '채팅', noindex: true })

  let body
  if (!validId || channel.isError) body = <p className="p-6 text-center text-sm text-secondary">채널을 찾을 수 없습니다.</p>
  else if (channel.isPending) body = <Spinner />
  else if (broadcastId === null) body = <p className="p-6 text-center text-sm text-secondary">지금 방송 중이 아닙니다.</p>
  else body = <ChatPanel chat={chat} ended={broadcast.data?.status === 'ENDED'} framed={false} className="h-dvh bg-body" />

  return (
    <div className="h-dvh bg-body text-primary">
      {body}
      <AuthDialog />
    </div>
  )
}
