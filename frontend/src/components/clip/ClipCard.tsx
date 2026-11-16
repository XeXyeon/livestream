import { Link } from 'react-router-dom'
import { Badge } from '@astryxdesign/core/Badge'
import type { ClipSummary } from '../../api/types'
import Thumbnail from '../common/Thumbnail'
import { formatDuration, formatRelative } from '../broadcast/format'

/** 클립 카드(§6.7 S9 · S6): 썸네일 · 길이 배지 · 제목 · 만든 사람 · 날짜. showChannel이면 채널 이름도 */
export default function ClipCard({ clip, showChannel = false }: { clip: ClipSummary; showChannel?: boolean }) {
  return (
    <Link to={`/clips/${clip.id}`} className="group block rounded-lg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent">
      <Thumbnail src={clip.thumbnailUrl} alt={clip.title}>
        <span className="absolute bottom-2 right-2 rounded bg-black/70 px-1.5 py-0.5 text-xs text-white">
          {formatDuration(clip.durationSeconds)}
        </span>
        {clip.visibility === 'PRIVATE' && <span className="absolute left-2 top-2"><Badge variant="warning" label="비공개" /></span>}
      </Thumbnail>
      <div className="mt-2">
        <p className="line-clamp-2 font-semibold text-primary group-hover:text-accent">{clip.title}</p>
        {showChannel && <p className="mt-0.5 text-sm text-secondary">{clip.channel.name}</p>}
        <p className="text-xs text-secondary">{clip.creator.nickname}님이 만듦 · {formatRelative(clip.createdAt)}</p>
      </div>
    </Link>
  )
}
