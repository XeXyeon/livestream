import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'
import type { BroadcastDetail } from '../../api/types'
import RecommendList from '../broadcast/RecommendList'

function recordingLabel(broadcast: BroadcastDetail): string | null {
  const recording = broadcast.recording
  if (!recording || recording.status === 'FAILED') return '다시보기가 없습니다'
  if (recording.status === 'FILE_DELETED') return '보관 기간이 지나 다시보기가 삭제되었습니다'
  if (recording.status !== 'READY') return '다시보기 준비 중'
  if (recording.visibility === 'PRIVATE' && !broadcast.isOwner) return '다시보기가 공개되지 않았습니다'
  return null
}

// §6.5 종료 화면: 안내 · 다시보기 버튼(READY면 활성) · 추천
export default function EndedView({ broadcast }: { broadcast: BroadcastDetail }) {
  const disabledLabel = recordingLabel(broadcast)
  return (
    <div className="flex flex-col items-center gap-6 rounded-lg border border-border bg-surface px-6 py-10 text-center">
      <div>
        <Heading level={2}>방송이 종료되었습니다</Heading>
        <p className="mt-1 text-sm text-secondary">{broadcast.channel.name}님의 방송을 시청해 주셔서 감사합니다.</p>
      </div>
      {disabledLabel || !broadcast.recording
        ? <Button label={disabledLabel ?? '다시보기가 없습니다'} variant="secondary" isDisabled />
        : <Button href={`/videos/${broadcast.recording.id}`} label="다시보기" variant="primary" />}
      <RecommendList broadcastId={broadcast.id} />
    </div>
  )
}
