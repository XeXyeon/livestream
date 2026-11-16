import { Link } from 'react-router-dom'
import { Users } from 'lucide-react'
import type { ChannelSummary } from '../../api/types'
import { formatCount } from '../broadcast/format'

/** 검색 결과의 채널 카드. 라이브 중이면 LIVE 표시와 시청자 수를 붙이고, 누르면 채널 페이지로 간다 */
export default function ChannelCard({ channel }: { channel: ChannelSummary }) {
  return (
    <Link
      to={`/channel/${channel.id}`}
      className="group block rounded-lg border border-border bg-surface p-4 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
    >
      <div className="flex items-center gap-2">
        <p className="min-w-0 flex-1 truncate font-semibold text-primary group-hover:text-accent">{channel.name}</p>
        {channel.isLive && (
          <span className="shrink-0 rounded bg-rose-600 px-1.5 py-0.5 text-xs font-bold text-white">LIVE</span>
        )}
      </div>
      <p className="mt-1 line-clamp-2 min-h-10 text-sm text-secondary">{channel.description ?? '소개가 없습니다.'}</p>
      <p className="mt-2 flex flex-wrap items-center gap-x-3 text-xs text-secondary">
        <span>{channel.category.label}</span>
        <span>팔로워 {formatCount(channel.followerCount)}</span>
        {channel.isLive && (
          <span className="flex items-center gap-1">
            <Users aria-hidden className="h-3 w-3" />
            <span className="sr-only">시청자</span>{formatCount(channel.viewerCount)}
          </span>
        )}
      </p>
    </Link>
  )
}
