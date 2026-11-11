import { Link } from 'react-router-dom'
import type { RecordingSummary } from '../../api/types'
import Thumbnail from '../common/Thumbnail'
import { formatDuration, formatRelative } from './format'

export default function RecordingCard({ recording }: { recording: RecordingSummary }) {
  return (
    <Link to={`/videos/${recording.id}`} className="group block">
      <Thumbnail src={recording.thumbnailUrl} alt={recording.title}>
        <span className="absolute bottom-2 right-2 rounded bg-black/70 px-1.5 py-0.5 text-xs text-white">
          {formatDuration(recording.durationSeconds)}
        </span>
      </Thumbnail>
      <div className="mt-2">
        <p className="line-clamp-2 font-semibold text-white group-hover:text-indigo-300">{recording.title}</p>
        <p className="mt-0.5 text-sm text-slate-400">{recording.channel.name}</p>
        <p className="text-xs text-slate-500">{recording.category.label} · {formatRelative(recording.startedAt)}</p>
      </div>
    </Link>
  )
}
