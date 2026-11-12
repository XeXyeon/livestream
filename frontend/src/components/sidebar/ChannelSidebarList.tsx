import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Avatar } from '@astryxdesign/core/Avatar'
import { formatCount, formatRelative } from '../broadcast/format'
import { useMyFollows } from '../follow/useMyFollows'
import { followsToSidebar } from './sidebarChannels'
import type { SidebarChannel } from './sidebarChannels'
import { useSidebarChannels } from './useSidebarChannels'

type Props = {
  /** 접힌 레일: 아바타만 */
  collapsed?: boolean
  /** 행을 누른 뒤(서랍 닫기 등) */
  onNavigate?: () => void
}

/** 사이드바 묶음 하나. 위에서부터 이 순서로 그린다 */
type SidebarGroup = {
  key: string
  title: string
  channels: SidebarChannel[]
  isPending: boolean
  isError: boolean
  empty: string
  /** 목록 대신 보일 안내(비로그인 "팔로우" 칸). 접힌 레일에서는 보이지 않는다 */
  notice?: ReactNode
}

/**
 * 사이드바 본문. 펼침(행) · 접힘(아바타 줄) 공용. 위에서부터 "팔로우"(FOLLOW-2) · "지금 라이브" · "추천 채널".
 */
export default function ChannelSidebarList({ collapsed = false, onNavigate }: Props) {
  const { live, recommended } = useSidebarChannels()
  const follows = useMyFollows()
  const { pathname, search } = useLocation()
  const followGroup: SidebarGroup = {
    key: 'follow',
    title: '팔로우',
    channels: follows.data ? followsToSidebar(follows.data) : [],
    isPending: !follows.ready || (follows.loggedIn && follows.isPending),
    isError: follows.isError && !follows.data,
    empty: '팔로우한 채널이 없습니다.',
    notice: follows.ready && !follows.loggedIn ? (
      <p className="px-3 py-1 text-xs text-secondary">
        로그인하면 팔로우한 채널이 여기 보입니다.{' '}
        <Link to={`/login?next=${encodeURIComponent(pathname + search)}`} onClick={onNavigate} className="font-medium text-accent hover:underline">로그인</Link>
      </p>
    ) : undefined,
  }
  const groups: SidebarGroup[] = [
    followGroup,
    { key: 'live', title: '지금 라이브', ...live, empty: '지금 방송 중인 채널이 없습니다.' },
    { key: 'recommended', title: '추천 채널', ...recommended, empty: '최근 방송한 채널이 없습니다.' },
  ]

  if (collapsed) {
    const visible = groups.filter((group) => group.channels.length > 0)
    return (
      <div className="flex flex-col items-center gap-3 py-2">
        {visible.map((group, index) => (
          <div key={group.key} className="flex flex-col items-center gap-3">
            {index > 0 && <hr aria-hidden className="w-8 border-border" />}
            <CollapsedGroup group={group} onNavigate={onNavigate} />
          </div>
        ))}
      </div>
    )
  }

  return (
    <div className="space-y-5 py-2">
      {groups.map((group) => <ExpandedGroup key={group.key} group={group} onNavigate={onNavigate} />)}
    </div>
  )
}

function CollapsedGroup({ group, onNavigate }: { group: SidebarGroup; onNavigate?: () => void }) {
  return (
    <ul aria-label={group.title} className="flex flex-col items-center gap-2">
      {group.channels.map((channel) => (
        <li key={channel.channelId}>
          <Link
            to={channel.href}
            onClick={onNavigate}
            title={channel.name}
            aria-label={`${channel.name}${channel.viewerCount != null ? ` · 라이브 · 시청자 ${formatCount(channel.viewerCount)}명` : ''}`}
            className="block rounded-full focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
          >
            <ChannelAvatar channel={channel} />
          </Link>
        </li>
      ))}
    </ul>
  )
}

function ExpandedGroup({ group, onNavigate }: { group: SidebarGroup; onNavigate?: () => void }) {
  let body
  if (group.notice) {
    body = group.notice
  } else if (group.channels.length > 0) {
    body = (
      <ul>
        {group.channels.map((channel) => (
          <li key={channel.channelId}>
            <Link
              to={channel.href}
              onClick={onNavigate}
              className="flex items-center gap-3 rounded-md px-3 py-2 hover:bg-muted focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent"
            >
              <ChannelAvatar channel={channel} />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-primary">{channel.name}</span>
                <span className="block truncate text-xs text-secondary">{channel.category.label}</span>
              </span>
              {channel.viewerCount != null ? (
                <span className="flex shrink-0 items-center gap-1 text-xs text-secondary">
                  <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-rose-600" />
                  <span className="sr-only">라이브 · 시청자</span>
                  {formatCount(channel.viewerCount)}
                </span>
              ) : (
                <span className="shrink-0 text-xs text-secondary">{formatRelative(channel.lastBroadcastAt)}</span>
              )}
            </Link>
          </li>
        ))}
      </ul>
    )
  } else {
    const text = group.isPending ? '불러오는 중…' : group.isError ? '목록을 불러오지 못했습니다.' : group.empty
    body = <p className="px-3 py-1 text-xs text-secondary">{text}</p>
  }
  return (
    <div>
      <h2 className="px-3 pb-1 text-xs font-semibold text-secondary">{group.title}</h2>
      {body}
    </div>
  )
}

function ChannelAvatar({ channel }: { channel: SidebarChannel }) {
  const isLive = channel.viewerCount != null
  return (
    <span className={`relative block shrink-0 rounded-full ${isLive ? 'ring-2 ring-rose-600 ring-offset-2 ring-offset-surface' : ''}`}>
      <Avatar name={channel.name} size={32} />
    </span>
  )
}
