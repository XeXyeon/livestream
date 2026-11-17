import { useQuery } from '@tanstack/react-query'
import { listChannelClips } from '../../api/clips'
import CardStrip from '../common/CardStrip'
import ClipCard from './ClipCard'

const STRIP_SIZE = 12

/**
 * 시청 화면 아래 "이 채널의 클립" 한 줄(넘김은 CardStrip). 클립이 없거나 못 불러오면 칸째 숨긴다.
 * "전체 보기"는 채널 "클립" 탭(`/channel/{id}?tab=clips`)으로 간다.
 */
export default function ClipStrip({ channelId }: { channelId: number }) {
  const query = useQuery({
    queryKey: ['clips', 'channel-strip', channelId, STRIP_SIZE],
    queryFn: () => listChannelClips(channelId, 0, STRIP_SIZE),
  })
  const items = query.data?.items ?? []
  if (items.length === 0) return null
  return (
    <CardStrip
      headingId={`clip-strip-${channelId}`}
      title="이 채널의 클립"
      moreTo={`/channel/${channelId}?tab=clips`}
      itemLabel="클립"
      items={items.map((clip) => ({ key: clip.id, node: <ClipCard clip={clip} /> }))}
    />
  )
}
