import { useRef, useState } from 'react'
import type { FormEvent, ReactNode, RefObject } from 'react'
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
export type ClipVideoEvents = { onLoadedMetadata: () => void; onPause: () => void; onSeeked: () => void; onTimeUpdate: () => void }

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
 *   미리보기 영상 → 구간 막대(ClipRangeBar 시작 · 끝 손잡이) → 시작 · 끝 위치(m:ss) → 길이 안내(5~60초) → 제목 · 공개 → 취소 · 만들기.
 * 구간 상태는 하나(start, duration)이고 막대 · 두 입력이 같은 상태를 바꾼다. 서버에는 startSeconds · durationSeconds(= 끝 − 시작)로 보낸다.
 * 재생: 영상이 준비되면 고른 구간을 반복 재생한다. 손잡이를 끄는 동안은 멈춘 채 그 손잡이 위치(시작 · 끝 화면)를 보여 주고,
 *   놓으면 다시 구간을 반복 재생한다(입력을 다 고쳤을 때도). 영상 타임라인을 직접 옮기면 시작이 그 위치로 간다(길이는 그대로).
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
  // 이 컴포넌트가 영상을 옮긴 것(막대 · 입력 · 반복 되감기)이면 시작을 따라가지 않는다(끝 손잡이를 끌 때 시작이 끝으로 튀지 않게)
  const selfSeekingRef = useRef(false)
  // 구간 반복 재생 중이면 그 구간(끝에 닿으면 시작으로 되감는다)
  const loopRef = useRef<ClipRangeValue | null>(null)

  const range = validateClipRange(startText, endText, totalSeconds)

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

  /** 멈춘 채 이 위치를 보여 준다(손잡이를 끄는 동안) */
  function showFrame(seconds: number) {
    const video = videoRef.current
    if (!video) return
    loopRef.current = null
    selfSeekingRef.current = true
    video.pause()
    video.currentTime = seconds
  }

  /** 구간 시작부터 재생하고 끝에 닿으면 시작으로 되감는다. 소리 때문에 자동 재생이 막히면 음소거로 다시 시도한다 */
  function loopRange(value: ClipRangeValue) {
    const video = videoRef.current
    if (!video) return
    loopRef.current = value
    selfSeekingRef.current = true
    video.currentTime = value.start
    video.play().catch(() => {
      video.muted = true
      video.play().catch(() => { loopRef.current = null })
    })
  }

  /** 끄는 동안: 시작 손잡이면 시작 화면, 끝 손잡이면 끝 화면(구간 통째로면 시작). 놓으면(handle null) 구간 반복 재생 */
  function changeFromBar(value: ClipRangeValue, handle: ClipHandle | null) {
    applyRange(value)
    if (handle !== null) showFrame(handleSeconds(value, handle))
    else loopRange(value)
  }

  /** 입력을 다 고쳤을 때: 구간이 올바르면 반복 재생, 아니면 적은 위치를 보여 준다 */
  function commitText(text: string) {
    if (range.ok) loopRange({ start: range.startSeconds, duration: range.durationSeconds })
    else {
      const seconds = parseClipTime(text)
      if (seconds !== null) showFrame(seconds)
    }
  }

  /** 영상 타임라인을 직접 옮기면(다시보기) 시작이 그 위치로(길이는 그대로, 영상 끝은 넘지 않게), 반복도 그 구간으로 */
  function onSeeked() {
    const video = videoRef.current
    if (!video) return
    setPlayhead(video.currentTime)
    if (selfSeekingRef.current) {
      // 아직 옮기는 중이면 마지막 이동이 끝날 때까지 건너뛴다
      if (!video.seeking) selfSeekingRef.current = false
      return
    }
    // 영상 끝을 넘지 않게: 끝 근처로 옮기면 길이만큼 앞에서 시작한다(막대 · 입력이 영상 밖으로 나가지 않게)
    const latest = total === null ? Infinity : Math.max(0, total - bar.duration)
    const next = { start: Math.min(Math.floor(video.currentTime), latest), duration: bar.duration }
    applyRange(next)
    if (loopRef.current) loopRef.current = next
  }

  function onTimeUpdate() {
    const video = videoRef.current
    if (!video) return
    setPlayhead(video.currentTime)
    const loop = loopRef.current
    // 재생이 구간 끝을 막 지났을 때만 되감는다. 끝보다 1초 넘게 뒤면 타임라인을 직접 옮긴 것이라 두고(onSeeked가 시작을 옮긴다),
    // 옮기는 중(seeking)에도 두지 않으면 그 이동을 되감기로 덮어 시작이 따라가지 못한다
    const loopEnd = loop ? loop.start + loop.duration : 0
    if (loop && !video.paused && !video.seeking && video.currentTime >= loopEnd && video.currentTime < loopEnd + 1) {
      selfSeekingRef.current = true
      video.currentTime = loop.start
    }
  }

  const videoEvents: ClipVideoEvents = {
    // 영상이 준비되면 고른 구간부터 반복 재생
    onLoadedMetadata: () => loopRange(bar),
    onPause: () => { if (videoRef.current) setPlayhead(videoRef.current.currentTime) },
    onSeeked,
    onTimeUpdate,
  }

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
        onBlur={() => commitText(value)}
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
        {/* 영상 바로 아래 막대, 그 아래 안내. 길이를 모르는 다시보기 · 5초보다 짧은 영상은 막대 없이 입력만 */}
        <div>
          {renderVideo(videoRef, videoEvents)}
          {view !== null && total !== null && total >= CLIP_MIN_SECONDS && (
            <>
              <div className="mt-3">
                <ClipRangeBar value={bar} total={total} view={view} currentSeconds={playhead} onChange={changeFromBar} />
              </div>
              <p className="mt-1 text-xs text-secondary">막대 양 끝을 끌어 구간을 고르세요. 놓으면 고른 구간이 반복 재생됩니다.</p>
            </>
          )}
        </div>
        <div>
          <div className="grid grid-cols-2 gap-4">
            {timeInput('시작 위치', startText, (text) => changeText(text, endText))}
            {timeInput('끝 위치', endText, (text) => changeText(startText, text))}
          </div>
          <p id="clip-range-hint" className="mt-2 text-xs text-secondary">{CLIP_LENGTH_NOTICE}</p>
          {!range.ok && <p role="alert" className="mt-1 text-sm text-error">{range.message}</p>}
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
