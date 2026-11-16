import { useRef, useState } from 'react'
import type { RefObject } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Scissors } from 'lucide-react'
import { Badge } from '@astryxdesign/core/Badge'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Heading } from '@astryxdesign/core/Heading'
import { ApiError } from '../api/client'
import { getRecording } from '../api/recordings'
import ClipDialog from '../components/clip/ClipDialog'
import ClipList from '../components/clip/ClipList'
import ClipProgress from '../components/clip/ClipProgress'
import { defaultClipStart } from '../components/clip/clipState'
import CommentList from '../components/comment/CommentList'
import { formatDuration, formatDateTime } from '../components/broadcast/format'
import Spinner from '../components/common/Spinner'
import RecordingVideo from '../components/recording/RecordingVideo'
import VisibilitySwitch from '../components/recording/VisibilitySwitch'
import { useAuth } from '../store/auth'
import { useAuthDialog } from '../store/authDialog'
import type { RecordingDetail } from '../api/types'
import { canChangeVisibility } from '../components/recording/recordingState'
import { usePageMeta } from '../components/layout/usePageMeta'


/**
 * S6 다시보기(§6.3 · §6.7). 댓글은 녹화가 READY · FILE_DELETED일 때만 쓸 수 있다(서버 409).
 * 클립(CLIP-2): READY면 "클립 만들기"(ClipDialog) · `?t=`(클립 → 원본 링크)는 그 위치부터 재생 · 아래 "이 영상의 클립".
 */
export default function Video() {
  const recordingId = Number(useParams().recordingId)
  const valid = Number.isInteger(recordingId) && recordingId > 0
  // 새로고침 직후에는 로그인 복구(refresh)가 끝난 뒤에 요청한다. 아니면 주인의 비공개 다시보기가 403으로 보이고 isOwner가 false로 남는다
  const authReady = useAuth((state) => state.ready)
  const [searchParams] = useSearchParams()
  const startAt = parseStartAt(searchParams.get('t'))
  const videoRef = useRef<HTMLVideoElement | null>(null)
  const [clipId, setClipId] = useState<number | null>(null)
  const query = useQuery({
    queryKey: ['recording', recordingId],
    queryFn: () => getRecording(recordingId),
    enabled: valid && authReady,
    // 병합 중이면 끝날 때까지 10초마다 다시 본다
    refetchInterval: (q) => (q.state.data?.status === 'RECORDING' || q.state.data?.status === 'PROCESSING' ? 10_000 : false),
  })
  usePageMeta({ title: query.data?.title ?? null, noindex: !valid || query.error instanceof ApiError })

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
      <RecordingVideo key={recording.id} recording={recording} startAt={startAt} videoRef={videoRef} />
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 space-y-2">
          <Heading level={1}>{recording.title}</Heading>
          <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-secondary">
            <Link to={`/channel/${recording.channel.id}`} className="font-medium text-primary hover:text-accent focus-visible:outline-2 focus-visible:outline-accent">
              {recording.channel.name}
            </Link>
            <Badge variant="neutral" label={recording.category.label} />
            <span>{formatDateTime(recording.startedAt)}</span>
            <span>{formatDuration(recording.durationSeconds)}</span>
            {recording.visibility === 'PRIVATE' && <Badge variant="warning" label="비공개" />}
            {recording.donationTotal > 0 && <span>후원 {recording.donationTotal.toLocaleString('ko-KR')}원</span>}
          </p>
        </div>
        <div className="flex flex-col items-end gap-3">
          {recording.status === 'READY' && <ClipCreate recording={recording} videoRef={videoRef} onCreated={setClipId} />}
          {recording.isOwner && canChangeVisibility(recording.status) && (
            <div className="rounded-lg border border-border bg-surface p-4">
              <VisibilitySwitch recordingId={recording.id} visibility={recording.visibility} />
            </div>
          )}
        </div>
      </div>
      {clipId !== null && <ClipProgress key={clipId} clipId={clipId} onDismiss={() => setClipId(null)} />}
      {recording.status === 'READY' && <ClipList source={{ kind: 'recording', id: recording.id }} heading="이 영상의 클립" />}
      <CommentList
        target={{ kind: 'recording', id: recording.id }}
        count={recording.commentCount}
        closedReason={recording.status === 'READY' || recording.status === 'FILE_DELETED' ? undefined : '다시보기가 준비되면 댓글을 쓸 수 있습니다.'}
      />
    </div>
  )
}

/** `?t=` 초(정수, 0 이상). 아니면 처음부터 */
function parseStartAt(value: string | null): number | undefined {
  if (value === null || !/^\d+$/.test(value)) return undefined
  const seconds = Number(value)
  return seconds > 0 ? seconds : undefined
}

/** "클립 만들기": 비로그인은 로그인 모달, 로그인하면 지금 재생 위치를 시작으로 ClipDialog(미리보기 영상 + 구간 막대) */
function ClipCreate({ recording, videoRef, onCreated }: {
  recording: RecordingDetail
  videoRef: RefObject<HTMLVideoElement | null>
  onCreated: (clipId: number) => void
}) {
  const loggedIn = useAuth((state) => state.member !== null)
  const openLogin = useAuthDialog((state) => state.open)
  const [open, setOpen] = useState(false)
  const [initialStart, setInitialStart] = useState(0)

  function start() {
    if (!loggedIn) { openLogin(); return }
    setInitialStart(defaultClipStart(videoRef.current?.currentTime ?? 0, recording.durationSeconds))
    // 다이얼로그 안 미리보기와 소리가 겹치지 않게 페이지 영상은 멈춘다
    videoRef.current?.pause()
    setOpen(true)
  }

  return (
    <>
      <Button label="클립 만들기" icon={<Scissors aria-hidden className="h-4 w-4" />} variant="secondary" onClick={start} />
      <ClipDialog mode="recording" recording={recording} initialStart={initialStart} isOpen={open} onOpenChange={setOpen} onCreated={onCreated} />
    </>
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
