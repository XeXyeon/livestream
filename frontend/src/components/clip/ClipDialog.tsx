import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button } from '@astryxdesign/core/Button'
import { Dialog, DialogHeader } from '@astryxdesign/core/Dialog'
import { Switch } from '@astryxdesign/core/Switch'
import { ApiError } from '../../api/client'
import { createRecordingClip } from '../../api/clips'
import type { RecordingDetail } from '../../api/types'
import { formatDuration } from '../broadcast/format'
import { inputClassName } from '../common/TextField'
import { CLIP_DEFAULT_SECONDS, CLIP_MAX_SECONDS, CLIP_MIN_SECONDS, CLIP_TITLE_MAX, validateClipRange } from './clipState'

type Props = {
  recording: Pick<RecordingDetail, 'id' | 'title' | 'durationSeconds'>
  /** 열 때 시작 위치 기본값(지금 재생 위치, 초) */
  initialStart: number
  isOpen: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (clipId: number) => void
}

/**
 * S6 "클립 만들기"(§6.7): 시작(= 지금 재생 위치, m:ss로 고칠 수 있음) · 길이 5~60초(기본 30) · 제목(기본 영상 제목) · 공개 여부.
 * 미리보기는 없다. 만들면 닫고 상태 알림(ClipProgress)으로 넘긴다.
 */
export default function ClipDialog({ isOpen, onOpenChange, ...rest }: Props) {
  return (
    <Dialog isOpen={isOpen} onOpenChange={onOpenChange} purpose="form" width={440}>
      {/* key: 열 때마다 지금 재생 위치로 새로 채운다 */}
      {isOpen && <ClipForm key={rest.initialStart} onClose={() => onOpenChange(false)} {...rest} />}
    </Dialog>
  )
}

function ClipForm({ recording, initialStart, onClose, onCreated }: Omit<Props, 'isOpen' | 'onOpenChange'> & { onClose: () => void }) {
  const [start, setStart] = useState(formatDuration(initialStart))
  const [duration, setDuration] = useState(CLIP_DEFAULT_SECONDS)
  const [title, setTitle] = useState(recording.title)
  const [isPublic, setIsPublic] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const range = validateClipRange(start, duration, recording.durationSeconds)
  const rangeError = range.ok ? '' : range.message

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!range.ok) return
    setSubmitting(true)
    setError('')
    try {
      const clip = await createRecordingClip(recording.id, {
        title: title.trim() || undefined,
        startSeconds: range.startSeconds,
        durationSeconds: duration,
        visibility: isPublic ? 'PUBLIC' : 'PRIVATE',
      })
      onCreated(clip.id)
      onClose()
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '클립을 만들지 못했습니다.')
    } finally {
      setSubmitting(false)
    }
  }

  const end = range.ok ? formatDuration(range.startSeconds + duration) : null

  return (
    <>
      <DialogHeader title="클립 만들기" />
      <form onSubmit={submit} className="space-y-5 px-2 pb-2" noValidate>
        <div className="grid gap-4 sm:grid-cols-2">
          <label className="block text-sm font-medium text-primary">
            시작 위치
            <input
              value={start}
              onChange={(event) => setStart(event.target.value)}
              inputMode="numeric"
              placeholder="0:00"
              aria-invalid={rangeError !== ''}
              aria-describedby="clip-range-hint"
              className={`mt-2 ${inputClassName}`}
            />
          </label>
          <label className="block text-sm font-medium text-primary">
            길이 <span className="font-normal text-secondary">{duration}초</span>
            <input
              type="range"
              min={CLIP_MIN_SECONDS}
              max={CLIP_MAX_SECONDS}
              step={1}
              value={duration}
              onChange={(event) => setDuration(Number(event.target.value))}
              aria-valuetext={`${duration}초`}
              className="mt-4 block w-full accent-[var(--color-accent)]"
            />
          </label>
        </div>
        <p id="clip-range-hint" className={`text-sm ${rangeError ? 'text-error' : 'text-secondary'}`} role={rangeError ? 'alert' : undefined}>
          {rangeError || `${start.trim()} ~ ${end} 구간을 클립으로 만듭니다.`}
        </p>
        <label className="block text-sm font-medium text-primary">
          제목
          <input
            value={title}
            maxLength={CLIP_TITLE_MAX}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={recording.title}
            className={`mt-2 ${inputClassName}`}
          />
        </label>
        <Switch
          label="클립 공개"
          description={isPublic ? '누구나 볼 수 있습니다' : '나와 채널 주인만 볼 수 있습니다'}
          value={isPublic}
          changeAction={(checked: boolean) => setIsPublic(checked)}
        />
        {error && <p role="alert" className="text-sm text-error">{error}</p>}
        <div className="flex justify-end gap-2">
          <Button label="취소" variant="ghost" onClick={onClose} />
          <Button type="submit" label="만들기" variant="primary" isLoading={submitting} isDisabled={!range.ok} />
        </div>
      </form>
    </>
  )
}
