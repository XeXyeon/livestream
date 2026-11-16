import { useCallback, useState } from 'react'
import type { FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AlertDialog } from '@astryxdesign/core/AlertDialog'
import { Badge } from '@astryxdesign/core/Badge'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Heading } from '@astryxdesign/core/Heading'
import { Switch } from '@astryxdesign/core/Switch'
import { ApiError } from '../api/client'
import { createClipVideoToken, deleteClip, updateClip } from '../api/clips'
import type { ClipDetail } from '../api/types'
import { formatDateTime, formatDuration } from '../components/broadcast/format'
import { clipPlayback, CLIP_TITLE_MAX } from '../components/clip/clipState'
import { useClipStatus } from '../components/clip/useClipStatus'
import Spinner from '../components/common/Spinner'
import { inputClassName } from '../components/common/TextField'
import TokenVideo from '../components/recording/TokenVideo'
import type { RecordingPlayback } from '../components/recording/recordingState'
import { useAuth } from '../store/auth'

const errorMessage = (cause: unknown, fallback: string) => (cause instanceof ApiError ? cause.message : fallback)

/**
 * S13 클립(§6.3 · §6.7). 만드는 중이면 2초마다 다시 부르고, 준비되면 소리 없이 자동 재생한다.
 * 만든 회원 · 채널 주인은 제목 · 공개 설정 · 삭제를 할 수 있다. 원본 다시보기 링크는 볼 수 있을 때만.
 */
export default function Clip() {
  const clipId = Number(useParams().clipId)
  const valid = Number.isInteger(clipId) && clipId > 0
  // 새로고침 직후에는 로그인 복구가 끝난 뒤 부른다(비공개 클립이 403으로 보이지 않게)
  const authReady = useAuth((state) => state.ready)
  const query = useClipStatus(valid ? clipId : null, authReady)

  if (!valid || (query.error instanceof ApiError && query.error.status === 404)) {
    return <NotAvailable title="클립을 찾을 수 없습니다" description="삭제되었거나 없는 주소입니다." />
  }
  if (query.error instanceof ApiError && query.error.status === 403) {
    return <NotAvailable title="비공개 클립입니다" description="만든 사람과 채널 주인만 볼 수 있습니다." />
  }
  if (query.isError) return <Banner role="alert" status="error" title="클립을 불러오지 못했습니다." description="잠시 뒤 다시 시도해 주세요." />
  if (!query.data) return <Spinner label="클립을 불러오는 중" />

  return <ClipView key={query.data.id} clip={query.data} />
}

function ClipView({ clip }: { clip: ClipDetail }) {
  const fetchToken = useCallback(() => createClipVideoToken(clip.id), [clip.id])
  const canManage = clip.isCreator || clip.isOwner

  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <TokenVideo
        mediaKey={`clip-${clip.id}-${clip.status}`}
        playback={clipPlayback(clip)}
        videoUrl={clip.videoUrl}
        poster={clip.thumbnailUrl}
        fetchToken={fetchToken}
        overlayText={OVERLAY_TEXT}
        autoPlayMuted
      />
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 space-y-2">
          <Heading level={1}>{clip.title}</Heading>
          <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-secondary">
            <Link to={`/channel/${clip.channel.id}`} className="font-medium text-primary hover:text-accent focus-visible:outline-2 focus-visible:outline-accent">
              {clip.channel.name}
            </Link>
            <span>{clip.creator.nickname}님이 만듦</span>
            <span>{formatDateTime(clip.createdAt)}</span>
            <span>{formatDuration(clip.durationSeconds)}</span>
            {clip.source === 'LIVE' && <Badge variant="neutral" label="라이브에서" />}
            {clip.visibility === 'PRIVATE' && <Badge variant="warning" label="비공개" />}
          </p>
          <div className="flex flex-wrap gap-2 pt-1">
            <CopyLinkButton clipId={clip.id} />
            {clip.recordingAvailable && clip.recordingId !== null && (
              <Button href={`/videos/${clip.recordingId}?t=${clip.startSeconds}`} label="원본 다시보기" variant="secondary" size="sm" />
            )}
          </div>
        </div>
      </div>
      {canManage && <ClipManage clip={clip} />}
    </div>
  )
}

const OVERLAY_TEXT: Record<RecordingPlayback, string> = {
  public: '클립을 불러오는 중…',
  private: '재생 주소를 받는 중…',
  unavailable: '지금은 클립을 재생할 수 없습니다.',
  processing: '클립을 만드는 중…',
  // failureReason은 내부 코드(ERROR · EMPTY …)라 화면에는 쓰지 않는다
  failed: '클립을 만들지 못했습니다. 다시 만들어 주세요.',
  deleted: '클립 영상이 없습니다.',
}

function CopyLinkButton({ clipId }: { clipId: number }) {
  const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle')
  async function copy() {
    try {
      await navigator.clipboard.writeText(`${window.location.origin}/clips/${clipId}`)
      setCopied('done')
    } catch {
      setCopied('failed')
    }
  }
  return (
    <span className="inline-flex items-center gap-2">
      <Button label="주소 복사" variant="secondary" size="sm" onClick={() => void copy()} />
      <span role="status" className="text-sm text-secondary">{copied === 'done' ? '복사했습니다' : copied === 'failed' ? '복사하지 못했습니다' : ''}</span>
    </span>
  )
}

/** 만든 회원 · 채널 주인: 제목 수정 · 공개 설정 · 삭제(§4.16 PATCH · DELETE /clips/{id}) */
function ClipManage({ clip }: { clip: ClipDetail }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [title, setTitle] = useState(clip.title)
  const [saving, setSaving] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState('')

  async function apply(body: { title?: string; visibility?: ClipDetail['visibility'] }) {
    setError('')
    const updated = await updateClip(clip.id, body)
    queryClient.setQueryData(['clip', clip.id], updated)
    await queryClient.invalidateQueries({ queryKey: ['clips'] })
  }

  async function saveTitle(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    try {
      await apply({ title: title.trim() })
    } catch (cause) {
      setError(errorMessage(cause, '제목을 저장하지 못했습니다.'))
    } finally {
      setSaving(false)
    }
  }

  async function changeVisibility(checked: boolean) {
    try {
      await apply({ visibility: checked ? 'PUBLIC' : 'PRIVATE' })
    } catch (cause) {
      setError(errorMessage(cause, '공개 설정을 바꾸지 못했습니다.'))
    }
  }

  async function remove() {
    setDeleting(true)
    setError('')
    try {
      await deleteClip(clip.id)
      setConfirmOpen(false)
      queryClient.removeQueries({ queryKey: ['clip', clip.id] })
      await queryClient.invalidateQueries({ queryKey: ['clips'] })
      void navigate(`/channel/${clip.channel.id}?tab=clips`, { replace: true })
    } catch (cause) {
      setError(errorMessage(cause, '클립을 삭제하지 못했습니다.'))
      setConfirmOpen(false)
    } finally {
      setDeleting(false)
    }
  }

  const titleChanged = title.trim() !== clip.title

  return (
    <section aria-labelledby="clip-manage" className="space-y-4 rounded-lg border border-border bg-surface p-5">
      <Heading level={2} id="clip-manage">클립 관리</Heading>
      <form onSubmit={saveTitle} className="flex flex-wrap items-end gap-2">
        <label className="min-w-0 flex-1 text-sm font-medium text-primary">
          제목
          <input value={title} maxLength={CLIP_TITLE_MAX} onChange={(event) => setTitle(event.target.value)} className={`mt-2 ${inputClassName}`} />
        </label>
        <Button type="submit" label="저장" variant="primary" isLoading={saving} isDisabled={!titleChanged} />
      </form>
      <p className="text-xs text-secondary">제목을 비우고 저장하면 방송 제목으로 돌아갑니다.</p>
      <Switch
        label="클립 공개"
        description={clip.visibility === 'PUBLIC' ? '누구나 볼 수 있습니다' : '나와 채널 주인만 볼 수 있습니다'}
        value={clip.visibility === 'PUBLIC'}
        changeAction={changeVisibility}
      />
      {error && <p role="alert" className="text-sm text-error">{error}</p>}
      <div className="flex justify-end border-t border-border pt-4">
        <Button label="클립 삭제" variant="destructive" size="sm" isDisabled={clip.status === 'PROCESSING'} onClick={() => setConfirmOpen(true)} />
      </div>
      <AlertDialog
        cancelLabel="취소"
        isOpen={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="클립을 삭제할까요?"
        description="클립 영상이 지워지며 되돌릴 수 없습니다. 원본 다시보기는 그대로 남습니다."
        actionLabel="삭제"
        actionVariant="destructive"
        isActionLoading={deleting}
        onAction={() => void remove()}
      />
    </section>
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
