import { useQuery } from '@tanstack/react-query'
import { listChannelRecordings } from '../../api/channels'
import CardStrip from '../common/CardStrip'
import RecordingCard from './RecordingCard'

export const RECORDING_STRIP_SIZE = 12

/**
 * 시청 화면 아래 "이 채널의 지난 동영상" 한 줄(넘김은 CardStrip). 목록은 채널 다시보기 탭과 같은
 * `/channels/{id}/broadcasts`(ENDED · READY · PUBLIC만)이고, 지금 보고 있는 방송의 녹화는 뺀다.
 * 항목이 없거나 못 불러오면 칸째 숨긴다. "전체 보기"는 채널 "다시보기" 탭으로 간다.
 */
export default function RecordingStrip({ channelId, currentBroadcastId }: { channelId: number; currentBroadcastId: number }) {
  const query = useQuery({
    queryKey: ['channel', channelId, 'recordings-strip', RECORDING_STRIP_SIZE],
    queryFn: () => listChannelRecordings(channelId, 0, RECORDING_STRIP_SIZE),
  })
  const items = (query.data?.items ?? []).filter((recording) => recording.broadcastId !== currentBroadcastId)
  if (items.length === 0) return null
  return (
    <CardStrip
      headingId={`recording-strip-${channelId}`}
      title="이 채널의 지난 동영상"
      moreTo={`/channel/${channelId}?tab=recordings`}
      itemLabel="동영상"
      items={items.map((recording) => ({ key: recording.id, node: <RecordingCard recording={recording} /> }))}
    />
  )
}
