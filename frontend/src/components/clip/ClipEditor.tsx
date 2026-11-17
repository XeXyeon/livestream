import { useRef, useState } from 'react'
import type { FormEvent, ReactNode, RefObject } from 'react'
import { Play } from 'lucide-react'
import { Button } from '@astryxdesign/core/Button'
import { Switch } from '@astryxdesign/core/Switch'
import { ApiError } from '../../api/client'
import type { ClipDetail, ClipRequest } from '../../api/types'
import { formatDuration } from '../broadcast/format'
import { inputClassName } from '../common/TextField'
import { CLIP_LENGTH_NOTICE, CLIP_MAX_SECONDS, CLIP_MIN_SECONDS, CLIP_TITLE_MAX, defaultClipDuration, parseClipTime, validateClipRange } from './clipState'
import ClipRangeBar from './ClipRangeBar'
import { handleSeconds, keepWindow, zoomWindow } from './clipRange'
import type { ClipHandle, ClipRangeValue, ClipView } from './clipRange'

/** 미리보기 영상에 붙일 이벤트(막대 ↔ 영상 연동 · 구간 재생) */
export type ClipVideoEvents = { onPause: () => void; onSeeked: () => void; onTimeUpdate: () => void }

export type ClipEditorProps = {
  /** 막대 끝(다시보기 길이 또는 라이브 초안 windowSeconds). null이면 막대를 숨기고 끝 검사는 서버에 맡긴다 */
  totalSeconds: number | null
  /** 처음 시작 위치(초). 끝 = 시작 + 기본 길이(30초, 영상이 더 짧으면 그 길이) */
  initialStart: number
  /** 다시보기: 확대 창(4분)만 보인다. 라이브: 막대 = [0, total] */
  zoom: boolean
  defaultTitle: string
  /** 미리보기 영상. ref와 이벤트를 영상 요소에 붙인다(초안 <video> · RecordingVideo) */
  renderVideo: (ref: RefObject<HTMLVideoElement | null>, events: ClipVideoEvents) => ReactNode
  submit: (body: ClipRequest) => Promise<ClipDetail>
  onClose: () => void
  onCreated: (clipId: number) => void
}

/**
 * 클립 만들기 화면(§6.7). 라이브 · 다시보기 어디서 열든 이 컴포넌트 하나로 같은 UI를 쓴다:
 *   미리보기 영상 → 구간 막대(ClipRangeBar 시작 · 끝 손잡이) + "구간 재생" → 시작 · 끝 위치(m:ss) → 길이 안내(5~60초) → 제목 · 공개 → 취소 · 만들기.
 * 구간 상태는 하나(start, duration)이고 막대 · 두 입력이 같은 상태를 바꾼다. 서버에는 startSeconds · durationSeconds(= 끝 − 시작)로 보낸다.
 * 막대를 끌면 영상이 손잡이 위치로 따라가고(일시정지), 영상을 멈추거나 옮기면 시작이 그 위치를 따른다(길이는 그대로).
 * 다이얼로그 안에서 본문만 세로 스크롤되고 취소 · 만들기 줄은 아래에 남는다(CLIP-4#2).
 */
export default function ClipEditor({ totalSeconds, initialStart, zoom, defaultTitle, renderVideo, submit, onClose, onCreated }: ClipEditorProps) {
  const total = totalSeconds === null ? null : Math.floor(totalSeconds)
  const initialDuration = defaultClipDuration(totalSeconds)
  const [startText, setStartText] = useState(formatDuration(initialStart))
  const [endText, setEndText] = useState(formatDuration(initialStart + initialDuration))
  // 막대가 그리는 구간: 입력이 잠깐 틀려도 마지막 올바른 구간을 유지한다
  const [bar, setBar] = useState<ClipRangeValue>({ start: initialStart, duration: initialDuration })
  const [view, setView] = useState<ClipView | null>(() => (total === null ? null : zoom ? zoomWindow(initialStart, total) : { start: 0, end: total }))
  const [title, setTitle] = useState(defaultTitle)
  const [isPublic, setIsPublic] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [playhead, setPlayhead] = useState<number | null>(null)

  const videoRef = useRef<HTMLVideoElement | null>(null)
  // 막대 · 입력 · "구간 재생"이 영상을 옮긴 것이면 시작을 따라가지 않는다(끝 손잡이를 끌 때 시작이 끝으로 튀지 않게)
  const selfSeekingRef = useRef(false)
  // "구간 재생" 중이면 멈출 위치(초)
  const rangeEndRef = useRef<number | null>(null)

  // 입력으로 확대 창 밖 구간을 넣으면 창을 다시 잡는다(렌더 중 상태 맞추기)
  if (zoom && view !== null && total !== null) {
    const next = keepWindow(view, bar, total)
    if (next !== view) setView(next)
  }

  function applyRange(value: ClipRangeValue) {
    setStartText(formatDuration(value.start))
    setEndText(formatDuration(value.start + value.duration))
    setBar(value)
  }

  /** 두 입력이 모두 읽히고 길이가 5~60초면 막대도 그 구간으로 */
  function changeText(nextStart: string, nextEnd: string) {
    setStartText(nextStart)
    setEndText(nextEnd)
    const start = parseClipTime(nextStart)
    const end = parseClipTime(nextEnd)
    if (start === null || end === null) return
    const duration = end - start
    if (duration >= CLIP_MIN_SECONDS && duration <= CLIP_MAX_SECONDS) setBar({ start, duration })
  }

  function seekVideo(seconds: number) {
    const video = videoRef.current
    if (!video) return
    selfSeekingRef.current = true
    rangeEndRef.current = null
    video.pause()
    video.currentTime = seconds
  }

  /** 막대로 옮기는 동안: 영상을 끄는 손잡이 위치로. 놓으면(handle null) 그 자리에 둔다 */
  function changeFromBar(value: ClipRangeValue, handle: ClipHandle | null) {
    applyRange(value)
    if (handle !== null) seekVideo(handleSeconds(value, handle))
  }

  /** 영상을 멈추거나 옮기면 시작이 그 위치로(길이는 그대로) */
  function followVideo() {
    const video = videoRef.current
    if (!video) return
    setPlayhead(video.currentTime)
    if (selfSeekingRef.current) {
      // 아직 옮기는 중이면 마지막 이동이 끝날 때까지 건너뛴다
      if (!video.seeking) selfSeekingRef.current = false
      return
    }
    rangeEndRef.current = null
    applyRange({ start: Math.floor(video.currentTime), duration: bar.duration })
  }

  /** 시작부터 재생해 끝에서 멈춘다(timeupdate) */
  function playRange() {
    const video = videoRef.current
    if (!video) return
    selfSeekingRef.current = true
    rangeEndRef.current = bar.start + bar.duration
    video.currentTime = bar.start
    void video.play().catch(() => { rangeEndRef.current = null })
  }

  function stopAtRangeEnd() {
    const video = videoRef.current
    if (!video) return
    setPlayhead(video.currentTime)
    if (rangeEndRef.current !== null && video.currentTime >= rangeEndRef.current) {
      rangeEndRef.current = null
      selfSeekingRef.current = true
      video.pause()
    }
  }

  const range = validateClipRange(startText, endText, totalSeconds)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!range.ok) return
    setSubmitting(true)
    setError('')
    try {
      const clip = await submit({
        title: title.trim() || undefined,
        startSeconds: range.startSeconds,
        durationSeconds: range.durationSeconds,
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

  const timeInput = (label: string, value: string, onChange: (text: string) => void) => (
    <label className="block text-sm font-medium text-primary">
      {label}
      <input
        value={value}
        onChange={(event) => onChange(event.target.value)}
        // 다 고치면 미리보기를 그 위치로
        onBlur={() => { const seconds = parseClipTime(value); if (seconds !== null) seekVideo(seconds) }}
        inputMode="numeric"
        placeholder="0:00"
        aria-invalid={!range.ok}
        aria-describedby="clip-range-hint"
        className={`mt-2 tabular-nums ${inputClassName}`}
      />
    </label>
  )

  return (
    // Dialog 안쪽은 max-height + overflow:hidden인 세로 flex다. 본문만 스크롤하고 취소 · 만들기 줄은 그 아래에 둔다.
    // 가로는 숨긴다: 시작 손잡이의 터치 영역(44px)이 막대 왼쪽 밖으로 나가 가로 스크롤이 생기지 않게
    <form onSubmit={onSubmit} className="flex min-h-0 flex-1 flex-col" noValidate>
      <div className="min-h-0 flex-1 space-y-5 overflow-y-auto overflow-x-hidden overscroll-contain px-2 pb-2">
        {renderVideo(videoRef, { onPause: followVideo, onSeeked: followVideo, onTimeUpdate: stopAtRangeEnd })}
        <p className="text-sm text-secondary">막대의 손잡이를 끌거나 영상을 멈춰 구간을 고르세요. 시작 · 끝 위치를 직접 적어도 됩니다.</p>
        {/* 길이를 모르는 다시보기 · 5초보다 짧은 영상은 막대 없이 입력만 */}
        {view !== null && total !== null && total >= CLIP_MIN_SECONDS && (
          <div>
            <ClipRangeBar value={bar} total={total} view={view} currentSeconds={playhead} onChange={changeFromBar} />
            <div className="mt-2 flex justify-end">
              <Button label="구간 재생" icon={<Play aria-hidden className="h-4 w-4" />} variant="secondary" size="sm" onClick={playRange} />
            </div>
          </div>
        )}
        <div>
          <div className="grid grid-cols-2 gap-4">
            {timeInput('시작 위치', startText, (text) => changeText(text, endText))}
            {timeInput('끝 위치', endText, (text) => changeText(startText, text))}
          </div>
          <p id="clip-range-hint" className="mt-2 text-sm" role={range.ok ? undefined : 'alert'}>
            {range.ok
              ? <span className="text-secondary">{`${startText.trim()} ~ ${endText.trim()} · ${range.durationSeconds}초`}</span>
              : <span className="text-error">{range.message}</span>}
            <span className="block text-xs text-secondary">{CLIP_LENGTH_NOTICE}</span>
          </p>
        </div>
        <label className="block text-sm font-medium text-primary">
          제목
          <input
            value={title}
            maxLength={CLIP_TITLE_MAX}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={defaultTitle}
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
      </div>
      <div className="flex shrink-0 justify-end gap-2 border-t border-border px-2 pb-2 pt-3">
        <Button label="취소" variant="ghost" onClick={onClose} />
        <Button type="submit" label="만들기" variant="primary" isLoading={submitting} isDisabled={!range.ok} />
      </div>
    </form>
  )
}
