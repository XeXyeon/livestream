import { useState } from 'react'
import type { FormEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AlertDialog } from '@astryxdesign/core/AlertDialog'
import { Badge } from '@astryxdesign/core/Badge'
import { Button } from '@astryxdesign/core/Button'
import { ApiError } from '../../api/client'
import { deleteBroadcast, updateBroadcast } from '../../api/broadcasts'
import type { MyBroadcastItem } from '../../api/types'
import { inputClassName } from '../common/TextField'
import Thumbnail from '../common/Thumbnail'
import VisibilitySwitch from '../recording/VisibilitySwitch'
import { BROADCAST_STATUS_BADGE, RECORDING_STATUS_TEXT, canChangeVisibility } from '../recording/recordingState'
import { formatDateTime } from '../broadcast/format'

const message = (cause: unknown, fallback: string) => (cause instanceof ApiError ? cause.message : fallback)

export default function MyBroadcastRow({ item }: { item: MyBroadcastItem }) {
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState(item.title)
  const [saving, setSaving] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState('')
  const status = BROADCAST_STATUS_BADGE[item.status]
  const recording = item.recording
  const titleId = `my-broadcast-${item.id}`

  const refresh = () => Promise.all([
    queryClient.invalidateQueries({ queryKey: ['me', 'broadcasts'] }),
    queryClient.invalidateQueries({ queryKey: ['me', 'donations'] }),
    queryClient.invalidateQueries({ queryKey: ['recordings'] }),
    queryClient.invalidateQueries({ queryKey: ['channel'] }),
    ...(recording ? [queryClient.invalidateQueries({ queryKey: ['recording', recording.id] })] : []),
  ])

  async function saveTitle(event: FormEvent) {
    event.preventDefault()
    const next = title.trim()
    if (!next) return
    setSaving(true)
    setError('')
    try {
      // 지난 방송은 제목만 바꿀 수 있다(카테고리는 409)
      await updateBroadcast(item.id, { title: next })
      setEditing(false)
      await refresh()
    } catch (cause) {
      setError(message(cause, '제목을 저장하지 못했습니다.'))
    } finally {
      setSaving(false)
    }
  }

  async function remove() {
    setDeleting(true)
    setError('')
    try {
      await deleteBroadcast(item.id)
      setConfirmOpen(false)
      if (recording) queryClient.removeQueries({ queryKey: ['recording', recording.id] })
      await refresh()
    } catch (cause) {
      setError(message(cause, '방송을 삭제하지 못했습니다.'))
      setConfirmOpen(false)
    } finally {
      setDeleting(false)
    }
  }

  return (
    <li className="flex flex-col gap-4 py-4 lg:flex-row" aria-labelledby={titleId}>
      <div className="w-full shrink-0 lg:w-44">
        <Thumbnail src={item.thumbnailUrl} alt="" />
      </div>
      <div className="min-w-0 flex-1 space-y-2">
        {editing ? (
          <form onSubmit={saveTitle} className="flex flex-wrap gap-2">
            <label htmlFor={`${titleId}-input`} className="sr-only">방송 제목</label>
            <input id={`${titleId}-input`} required maxLength={100} value={title} onChange={(event) => setTitle(event.target.value)} className={`min-w-0 flex-1 ${inputClassName}`} />
            <Button type="submit" label="저장" variant="primary" size="sm" isLoading={saving} isDisabled={!title.trim()} />
            <Button label="취소" variant="ghost" size="sm" onClick={() => { setEditing(false); setTitle(item.title); setError('') }} />
          </form>
        ) : (
          <p id={titleId} className="break-words font-semibold text-primary">{item.title}</p>
        )}
        <p className="flex flex-wrap items-center gap-2 text-sm text-secondary">
          <Badge variant={status.variant} label={status.label} />
          <span>{item.category.label}</span>
          <span>{formatDateTime(item.startedAt)}</span>
        </p>
        <p className="text-sm text-secondary">
          {recording ? RECORDING_STATUS_TEXT[recording.status] : '다시보기 없음'}
          {recording?.status === 'READY' && (
            <> · <Link to={`/videos/${recording.id}`} className="text-accent hover:underline focus-visible:outline-2 focus-visible:outline-accent">다시보기 보기</Link></>
          )}
        </p>
        {recording && canChangeVisibility(recording.status) && (
          <VisibilitySwitch recordingId={recording.id} visibility={recording.visibility} size="sm" />
        )}
        {error && <p role="alert" className="text-sm text-error">{error}</p>}
      </div>
      <div className="flex shrink-0 gap-2 lg:flex-col">
        {!editing && <Button label="제목 수정" variant="secondary" size="sm" onClick={() => { setTitle(item.title); setEditing(true) }} />}
        <Button label="삭제" variant="destructive" size="sm" onClick={() => setConfirmOpen(true)} />
      </div>
      <AlertDialog
        cancelLabel="취소"
        isOpen={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="방송을 삭제할까요?"
        description={`${item.status === 'ENDED' ? '' : '진행 중인 방송은 먼저 종료됩니다. '}다시보기 영상도 함께 지워지며 되돌릴 수 없습니다. 받은 후원 내역은 남습니다.`}
        actionLabel="삭제"
        actionVariant="destructive"
        isActionLoading={deleting}
        onAction={() => void remove()}
      />
    </li>
  )
}
