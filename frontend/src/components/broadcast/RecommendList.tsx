import { useQuery } from '@tanstack/react-query'
import { getRecommendations } from '../../api/broadcasts'
import Spinner from '../common/Spinner'
import LiveCard from './LiveCard'
import RecordingCard from './RecordingCard'

// §4.16 추천: 다른 채널 라이브(같은 카테고리 우선) 최대 4, 없으면 최근 공개 다시보기 4
export default function RecommendList({ broadcastId }: { broadcastId: number }) {
  const { data, isPending, isError } = useQuery({
    queryKey: ['broadcast', broadcastId, 'recommendations'],
    queryFn: () => getRecommendations(broadcastId),
  })
  if (isPending) return <Spinner label="추천을 불러오는 중" />
  if (isError) return null
  const hasLives = data.lives.length > 0
  if (!hasLives && data.recordings.length === 0) return null
  return (
    <section className="w-full text-left">
      <h3 className="mb-3 text-sm font-semibold text-secondary">{hasLives ? '지금 방송 중인 다른 채널' : '추천 다시보기'}</h3>
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        {hasLives
          ? data.lives.map((live) => <LiveCard key={live.id} broadcast={live} />)
          : data.recordings.map((recording) => <RecordingCard key={recording.id} recording={recording} />)}
      </div>
    </section>
  )
}
