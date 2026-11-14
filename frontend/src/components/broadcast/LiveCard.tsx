import { Link } from 'react-router-dom'
import { Users } from 'lucide-react'
import type { BroadcastSummary } from '../../api/types'
import Thumbnail from '../common/Thumbnail'
import { formatCount, formatRelative } from './format'

export default function LiveCard({ broadcast }: { broadcast: BroadcastSummary }) {
  return (
    <Link to={`/live/${broadcast.channel.id}`} className="group block rounded-lg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent">
      <Thumbnail src={broadcast.thumbnailUrl} alt={broadcast.title}>
        <span className="absolute left-2 top-2 rounded bg-rose-600 px-1.5 py-0.5 text-xs font-bold text-white">
          {broadcast.status === 'DISCONNECTED' ? '재연결 중' : 'LIVE'}
        </span>
        <span className="absolute bottom-2 left-2 flex items-center gap-1 rounded bg-black/70 px-1.5 py-0.5 text-xs text-white">
          <Users aria-hidden className="h-3 w-3" />
          <span className="sr-only">시청자</span>{formatCount(broadcast.viewerCount)}
        </span>
      </Thumbnail>
      <div className="mt-2">
        <p className="line-clamp-2 font-semibold text-primary group-hover:text-accent">{broadcast.title}</p>
        <p className="mt-0.5 text-sm text-secondary">{broadcast.channel.name}</p>
        <p className="text-xs text-secondary">{broadcast.category.label} · {formatRelative(broadcast.startedAt)} 시작</p>
      </div>
    </Link>
  )
}
