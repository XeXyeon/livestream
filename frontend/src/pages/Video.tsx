import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { Badge } from '@astryxdesign/core/Badge'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Heading } from '@astryxdesign/core/Heading'
import { ApiError } from '../api/client'
import { getRecording } from '../api/recordings'
import CommentList from '../components/comment/CommentList'
import { formatDuration } from '../components/broadcast/format'
import Spinner from '../components/common/Spinner'
import RecordingVideo from '../components/recording/RecordingVideo'
import VisibilitySwitch from '../components/recording/VisibilitySwitch'
import { useAuth } from '../store/auth'
import { canChangeVisibility } from '../components/recording/recordingState'

const date = (iso: string) => new Date(iso).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })

/** S6 다시보기(§6.3 · §6.7). 댓글은 녹화가 READY · FILE_DELETED일 때만 쓸 수 있다(서버 409) */
export default function Video() {
  const recordingId = Number(useParams().recordingId)
  const valid = Number.isInteger(recordingId) && recordingId > 0
  // 새로고침 직후에는 로그인 복구(refresh)가 끝난 뒤에 요청한다. 아니면 주인의 비공개 다시보기가 403으로 보이고 isOwner가 false로 남는다
  const authReady = useAuth((state) => state.ready)
  const query = useQuery({
    queryKey: ['recording', recordingId],
    queryFn: () => getRecording(recordingId),
    enabled: valid && authReady,
    // 병합 중이면 끝날 때까지 10초마다 다시 본다
    refetchInterval: (q) => (q.state.data?.status === 'RECORDING' || q.state.data?.status === 'PROCESSING' ? 10_000 : false),
  })

  if (!valid || (query.error instanceof ApiError && query.error.status === 404)) {
    return <NotAvailable title="다시보기를 찾을 수 없습니다" description="삭제되었거나 없는 주소입니다." />
  }
  if (query.error instanceof ApiError && query.error.status === 403) {
    return <NotAvailable title="비공개 다시보기입니다" description="채널 주인만 볼 수 있습니다." />
  }
  if (query.isError) return <Banner role="alert" status="error" title="다시보기를 불러오지 못했습니다." description="잠시 뒤 다시 시도해 주세요." />
  if (!query.data) return <Spinner label="다시보기를 불러오는 중" />

  const recording = query.data
  return (
    <div className="space-y-5">
      <RecordingVideo key={recording.id} recording={recording} />
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 space-y-2">
          <Heading level={1}>{recording.title}</Heading>
          <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-secondary">
            <Link to={`/channel/${recording.channel.id}`} className="font-medium text-primary hover:text-accent focus-visible:outline-2 focus-visible:outline-accent">
              {recording.channel.name}
            </Link>
            <Badge variant="neutral" label={recording.category.label} />
            <span>{date(recording.startedAt)}</span>
            <span>{formatDuration(recording.durationSeconds)}</span>
            {recording.visibility === 'PRIVATE' && <Badge variant="warning" label="비공개" />}
            {recording.donationTotal > 0 && <span>후원 {recording.donationTotal.toLocaleString('ko-KR')}원</span>}
          </p>
        </div>
        {recording.isOwner && canChangeVisibility(recording.status) && (
          <div className="rounded-lg border border-border bg-surface p-4">
            <VisibilitySwitch recordingId={recording.id} visibility={recording.visibility} />
          </div>
        )}
      </div>
      <CommentList
        target={{ kind: 'recording', id: recording.id }}
        count={recording.commentCount}
        closedReason={recording.status === 'READY' || recording.status === 'FILE_DELETED' ? undefined : '다시보기가 준비되면 댓글을 쓸 수 있습니다.'}
      />
    </div>
  )
}

function NotAvailable({ title, description }: { title: string; description: string }) {
  return (
    <div className="space-y-4">
      <EmptyState headingLevel={1} title={title} description={description} />
      <div className="flex justify-center"><Button href="/" label="홈으로" variant="secondary" /></div>
    </div>
  )
}
