import { Link } from 'react-router-dom'
import type { RecordingSummary } from '../../api/types'
import Thumbnail from '../common/Thumbnail'
import { formatDuration, formatRelative } from './format'

export default function RecordingCard({ recording }: { recording: RecordingSummary }) {
  return (
    <Link to={`/videos/${recording.id}`} className="group block rounded-lg focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent">
      <Thumbnail src={recording.thumbnailUrl} alt={recording.title}>
        <span className="absolute bottom-2 right-2 rounded bg-black/70 px-1.5 py-0.5 text-xs text-white">
          {formatDuration(recording.durationSeconds)}
        </span>
      </Thumbnail>
      <div className="mt-2">
        <p className="line-clamp-2 font-semibold text-primary group-hover:text-accent">{recording.title}</p>
        <p className="mt-0.5 text-sm text-secondary">{recording.channel.name}</p>
        <p className="text-xs text-secondary">{recording.category.label} · {formatRelative(recording.startedAt)}</p>
      </div>
    </Link>
  )
}
