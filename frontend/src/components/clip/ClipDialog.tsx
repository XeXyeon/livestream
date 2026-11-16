import { useRef, useState } from 'react'
import type { FormEvent, ReactNode, RefObject } from 'react'
import { Play, X } from 'lucide-react'
import { Button } from '@astryxdesign/core/Button'
import { Dialog, DialogHeader } from '@astryxdesign/core/Dialog'
import { IconButton } from '@astryxdesign/core/IconButton'
import { Switch } from '@astryxdesign/core/Switch'
import { ApiError } from '../../api/client'
import { createClipFromDraft, createRecordingClip } from '../../api/clips'
import type { ClipDetail, ClipDraftDetail, ClipRequest, RecordingDetail } from '../../api/types'
import { formatDuration } from '../broadcast/format'
import Spinner from '../common/Spinner'
import RecordingVideo from '../recording/RecordingVideo'
import { inputClassName } from '../common/TextField'
import {
  CLIP_MAX_SECONDS, CLIP_MIN_SECONDS, CLIP_TITLE_MAX, defaultClipDuration, defaultLiveClipStart, parseClipTime, validateClipRange,
} from './clipState'
import ClipRangeBar from './ClipRangeBar'
import { handleSeconds, keepWindow, zoomWindow } from './clipRange'
import type { ClipHandle, ClipRangeValue, ClipView } from './clipRange'
import { useClipDraft } from './useClipDraft'

type Common = {
  isOpen: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (clipId: number) => void
}

type RecordingProps = Common & {
  mode: 'recording'
  /** 다이얼로그 안 미리보기도 같은 재생 규칙(공개 videoUrl · 비공개 video-token)으로 튼다 */
  recording: RecordingDetail
  /** 열 때 시작 위치 기본값(지금 재생 위치, 초) */
  initialStart: number
}

type LiveProps = Common & {
  mode: 'live'
  /** POST /broadcasts/{id}/clip-drafts 로 만든 초안 */
  draftId: number
  /** 제목 기본값(방송 제목) */
  defaultTitle: string
}

/**
 * 클립 만들기 다이얼로그(§6.7). 두 모드가 같은 구조 · 같은 조작이다(CLIP-4#1):
 *   미리보기 영상 · 구간 막대(ClipRangeBar) + "구간 재생" · 시작 m:ss · 길이 슬라이더(5~60초, 1초 단위) · 제목 · 공개 여부.
 *   막대를 끌면 영상이 손잡이 위치로 따라가고(일시정지), 영상을 멈추거나 옮기면 시작이 그 위치를 따른다.
 * - recording(S6 "클립 만들기"): 미리보기 = 이 다시보기(RecordingVideo, 시작 = 열 때의 재생 위치). 영상이 길어 막대는 확대 창(4분)만 보인다.
 * - live(S2 "클립", CLIP-3): 초안(최근 2분)을 받는 동안 "준비하는 중", READY면 초안 영상이 미리보기, FAILED면 안내.
 *   닫아도 초안은 그대로 두고(서버가 10분 뒤 지움) 다시 누르면 새 초안을 만든다. 막대 = 초안 전체.
 * 휴대폰에서 본문이 화면보다 길면 본문만 세로로 스크롤되고 취소 · 만들기 줄은 아래에 남는다(CLIP-4#2).
 * 만들면 닫고 상태 알림(ClipProgress)으로 넘긴다.
 */
export default function ClipDialog(props: RecordingProps | LiveProps) {
  const { isOpen, onOpenChange, onCreated } = props
  const onClose = () => onOpenChange(false)
  return (
    <Dialog isOpen={isOpen} onOpenChange={onOpenChange} purpose="form" width={640} maxHeight="90dvh">
      {/* key: 열 때마다 새로 채운다(다시보기는 지금 재생 위치, 라이브는 새 초안) */}
      {isOpen && props.mode === 'recording' && (
        <RecordingClipForm key={props.initialStart} recording={props.recording} initialStart={props.initialStart} onClose={onClose} onCreated={onCreated} />
      )}
      {isOpen && props.mode === 'live' && (
        <LiveDraftClip key={props.draftId} draftId={props.draftId} defaultTitle={props.defaultTitle} onClose={onClose} onCreated={onCreated} />
      )}
    </Dialog>
  )
}

type Done = { onClose: () => void; onCreated: (clipId: number) => void }

/** 제목 줄 오른쪽에 닫기(X). DialogHeader 기본 닫기 버튼은 이름이 영어(Close)라 직접 둔다 */
function ClipDialogHeader({ onClose }: { onClose: () => void }) {
  return (
    <DialogHeader
      title="클립 만들기"
      endContent={<IconButton label="닫기" icon={<X aria-hidden className="h-5 w-5" />} variant="ghost" size="sm" onClick={onClose} />}
    />
  )
}

function LiveDraftClip({ draftId, defaultTitle, onClose, onCreated }: { draftId: number; defaultTitle: string } & Done) {
  const { data, isError } = useClipDraft(draftId)
  return <LiveDraftStage draft={data ?? null} isError={isError} defaultTitle={defaultTitle} onClose={onClose} onCreated={onCreated} />
}

/**
 * 라이브 모드 본문: 초안 상태별 화면. 상태를 props로 받아 그리므로 테스트에서 그대로 렌더한다.
 * 초안 영상은 토큰이 주소에 있어 TokenVideo(토큰 재시도)가 필요 없다.
 */
export function LiveDraftStage({ draft, isError, defaultTitle, onClose, onCreated }: {
  draft: ClipDraftDetail | null
  isError: boolean
  defaultTitle: string
} & Done) {
  if (isError || draft?.status === 'FAILED' || (draft?.status === 'READY' && !draft.videoUrl)) {
    return (
      <>
        <ClipDialogHeader onClose={onClose} />
        <div className="space-y-5 px-2 pb-2">
          <p role="alert" className="text-sm text-error">클립을 만들 수 없습니다. 잠시 뒤 다시 시도해 주세요.</p>
          <div className="flex justify-end"><Button label="닫기" variant="secondary" onClick={onClose} /></div>
        </div>
      </>
    )
  }
  if (draft === null || draft.status === 'PENDING') {
    return (
      <>
        <ClipDialogHeader onClose={onClose} />
        <div className="space-y-5 px-2 pb-2">
          <div className="flex aspect-video w-full items-center justify-center rounded-md bg-surface">
            <Spinner label="최근 2분을 준비하는 중…" />
          </div>
          <div className="flex justify-end"><Button label="취소" variant="ghost" onClick={onClose} /></div>
        </div>
      </>
    )
  }
  return <LiveDraftForm draft={draft} videoUrl={draft.videoUrl ?? ''} defaultTitle={defaultTitle} onClose={onClose} onCreated={onCreated} />
}

/** 다시보기 모드: 미리보기는 이 다시보기, 막대는 확대 창 */
export function RecordingClipForm({ recording, initialStart, onClose, onCreated }: {
  recording: RecordingDetail
  initialStart: number
} & Done) {
  return (
    <PreviewClipForm
      totalSeconds={recording.durationSeconds}
      initialStart={initialStart}
      zoom
      defaultTitle={recording.title}
      renderVideo={(ref, events) => <RecordingVideo recording={recording} startAt={initialStart} videoRef={ref} mediaEvents={events} />}
      submit={(body) => createRecordingClip(recording.id, body)}
      onClose={onClose}
      onCreated={onCreated}
    />
  )
}

/** 라이브 모드(READY): 미리보기는 초안 영상, 막대는 초안 전체 */
function LiveDraftForm({ draft, videoUrl, defaultTitle, onClose, onCreated }: {
  draft: ClipDraftDetail
  videoUrl: string
  defaultTitle: string
} & Done) {
  const initialStart = defaultLiveClipStart(draft.windowSeconds)
  return (
    <PreviewClipForm
      totalSeconds={draft.windowSeconds}
      initialStart={initialStart}
      zoom={false}
      defaultTitle={defaultTitle}
      renderVideo={(ref, events) => (
        <video
          ref={ref}
          src={videoUrl}
          controls
          playsInline
          preload="metadata"
          // 다시보기 모드처럼 처음 화면을 클립 시작 위치로
          onLoadedMetadata={(event) => { event.currentTarget.currentTime = initialStart }}
          {...events}
          className="aspect-video w-full rounded-md bg-black"
        />
      )}
      submit={(body) => createClipFromDraft(draft.id, body)}
      onClose={onClose}
      onCreated={onCreated}
    />
  )
}

type VideoEvents = { onPause: () => void; onSeeked: () => void; onTimeUpdate: () => void }

/**
 * 두 모드가 함께 쓰는 "미리보기 영상 + 선택 폼". 영상 요소는 모드마다 다르고(초안 <video> · RecordingVideo),
 * 여기서는 ref와 이벤트만 넘겨 막대 ↔ 영상 연동 · 구간 재생을 한 코드로 한다.
 */
function PreviewClipForm({ totalSeconds, initialStart, zoom, defaultTitle, renderVideo, submit, onClose, onCreated }: {
  totalSeconds: number | null
  initialStart: number
  /** 다시보기: 확대 창(4분), 라이브: 막대 = [0, total] */
  zoom: boolean
  defaultTitle: string
  renderVideo: (ref: RefObject<HTMLVideoElement | null>, events: VideoEvents) => ReactNode
  submit: (body: ClipRequest) => Promise<ClipDetail>
} & Done) {
  const videoRef = useRef<HTMLVideoElement | null>(null)
  // 시작은 영상을 멈추거나 옮긴 위치를 따른다(입력란으로도 고칠 수 있다)
  const [seekTo, setSeekTo] = useState<number | null>(null)
  const [playhead, setPlayhead] = useState<number | null>(null)
  // 막대 · "구간 재생"이 영상을 옮긴 것이면 시작을 따라가지 않는다(끝 손잡이를 끌 때 시작이 끝으로 튀지 않게)
  const barSeekingRef = useRef(false)
  // "구간 재생" 중이면 멈출 위치(초)
  const rangeEndRef = useRef<number | null>(null)

  const followVideo = () => {
    const video = videoRef.current
    if (!video) return
    setPlayhead(video.currentTime)
    if (barSeekingRef.current) {
      // 아직 옮기는 중이면 마지막 이동이 끝날 때까지 건너뛴다
      if (!video.seeking) barSeekingRef.current = false
      return
    }
    rangeEndRef.current = null
    setSeekTo(Math.floor(video.currentTime))
  }

  /** 막대로 옮기는 동안: 영상을 멈추고 끄는 손잡이 위치로 옮긴다. 놓으면 그 자리에 둔다 */
  const syncVideo = (value: ClipRangeValue, handle: ClipHandle | null) => {
    const video = videoRef.current
    if (!video || handle === null) return
    barSeekingRef.current = true
    rangeEndRef.current = null
    video.pause()
    video.currentTime = handleSeconds(value, handle)
  }

  /** 시작부터 재생해 끝에서 멈춘다(timeupdate) */
  const playRange = (value: ClipRangeValue) => {
    const video = videoRef.current
    if (!video) return
    barSeekingRef.current = true
    rangeEndRef.current = value.start + value.duration
    video.currentTime = value.start
    void video.play().catch(() => { rangeEndRef.current = null })
  }

  const stopAtRangeEnd = () => {
    const video = videoRef.current
    if (!video) return
    setPlayhead(video.currentTime)
    if (rangeEndRef.current !== null && video.currentTime >= rangeEndRef.current) {
      rangeEndRef.current = null
      barSeekingRef.current = true
      video.pause()
    }
  }

  return (
    <ClipForm
      totalSeconds={totalSeconds}
      initialStart={initialStart}
      followStart={seekTo}
      onStartCommitted={(seconds) => { if (videoRef.current) videoRef.current.currentTime = seconds }}
      defaultTitle={defaultTitle}
      hint="막대 손잡이나 시작 위치 · 길이 슬라이더를 끌거나 영상을 멈춰 구간을 고르세요. 시작 위치는 직접 적어도 됩니다."
      preview={renderVideo(videoRef, { onPause: followVideo, onSeeked: followVideo, onTimeUpdate: stopAtRangeEnd })}
      bar={{
        zoom,
        currentSeconds: playhead,
        onChange: syncVideo,
        actions: (value) => <Button label="구간 재생" icon={<Play aria-hidden className="h-4 w-4" />} variant="secondary" size="sm" onClick={() => playRange(value)} />,
      }}
      submit={submit}
      onClose={onClose}
      onCreated={onCreated}
    />
  )
}

/** 두 모드가 함께 쓰는 선택 UI(시작 m:ss · 길이 슬라이더 · 제목 · 공개 여부) */
function ClipForm({ totalSeconds, initialStart, followStart = null, onStartCommitted, defaultTitle, hint, preview, bar, submit, onClose, onCreated }: {
  /** 다시보기 길이 또는 초안 windowSeconds. null이면 끝 검사는 서버에 맡긴다 */
  totalSeconds: number | null
  initialStart: number
  /** 바뀌면 시작 입력란을 이 값(초)으로 바꾼다(라이브 미리보기의 재생 위치) */
  followStart?: number | null
  /** 시작 입력란을 다 고쳤을 때(blur) 올바른 값이면 부른다 */
  onStartCommitted?: (seconds: number) => void
  defaultTitle: string
  hint?: string
  preview?: ReactNode
  /** 구간 막대(ClipRangeBar). zoom: 다시보기 확대 창(4분), 아니면 막대 = [0, total] */
  bar: {
    zoom: boolean
    currentSeconds?: number | null
    /** 막대로 값이 바뀔 때(라이브: 초안 영상을 손잡이 위치로) */
    onChange?: (value: ClipRangeValue, handle: ClipHandle | null) => void
    /** 막대 아래 버튼(라이브 "구간 재생") */
    actions?: (value: ClipRangeValue) => ReactNode
  }
  submit: (body: ClipRequest) => Promise<ClipDetail>
} & Done) {
  const [start, setStart] = useState(formatDuration(initialStart))
  // 막대가 그리는 시작(초): 입력란이 잠깐 틀린 형식이어도 마지막 올바른 값을 유지한다
  const [barStart, setBarStart] = useState(initialStart)
  const [followed, setFollowed] = useState<number | null>(followStart)
  const [duration, setDuration] = useState(defaultClipDuration(totalSeconds))
  const total = totalSeconds === null ? null : Math.floor(totalSeconds)
  const [view, setView] = useState<ClipView | null>(() => (total === null ? null : bar.zoom ? zoomWindow(initialStart, total) : { start: 0, end: total }))
  const [title, setTitle] = useState(defaultTitle)
  const [isPublic, setIsPublic] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  // 미리보기 위치가 바뀌면 시작을 따라간다(렌더 중 상태 맞추기: 이전 값과 다를 때만)
  if (followStart !== followed) {
    setFollowed(followStart)
    if (followStart !== null) {
      setStart(formatDuration(followStart))
      setBarStart(followStart)
    }
  }
  const barValue: ClipRangeValue = { start: barStart, duration }
  // 입력 · 슬라이더로 확대 창 밖 값을 넣으면 창을 다시 잡는다(렌더 중 상태 맞추기)
  if (bar.zoom && view !== null && total !== null) {
    const next = keepWindow(view, barValue, total)
    if (next !== view) setView(next)
  }

  function changeStartText(text: string) {
    setStart(text)
    const seconds = parseClipTime(text)
    if (seconds !== null) setBarStart(seconds)
  }

  function changeFromBar(value: ClipRangeValue, handle: ClipHandle | null) {
    setStart(formatDuration(value.start))
    setBarStart(value.start)
    setDuration(value.duration)
    bar.onChange?.(value, handle)
  }

  const range = validateClipRange(start, duration, totalSeconds)
  const rangeError = range.ok ? '' : range.message

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    if (!range.ok) return
    setSubmitting(true)
    setError('')
    try {
      const clip = await submit({
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
  // 시작 슬라이더 끝: 시작 + 길이가 영상 끝을 넘지 않게. 길이를 모르면 슬라이더를 두지 않는다
  const startMax = total === null ? null : Math.max(0, total - duration)

  return (
    <>
      <ClipDialogHeader onClose={onClose} />
      {/* Dialog 안쪽은 max-height + overflow:hidden인 세로 flex다. 본문만 스크롤하고 취소 · 만들기 줄은 그 아래에 둔다(CLIP-4#2).
          가로는 숨긴다: 시작 손잡이의 터치 영역(44px)이 막대 왼쪽 밖으로 나가 가로 스크롤이 생기지 않게 */}
      <form onSubmit={onSubmit} className="flex min-h-0 flex-1 flex-col" noValidate>
        <div className="min-h-0 flex-1 space-y-5 overflow-y-auto overflow-x-hidden overscroll-contain px-2 pb-2">
          {preview}
          {hint && <p className="text-sm text-secondary">{hint}</p>}
          {/* 길이를 모르는 다시보기 · 5초보다 짧은 영상은 막대 없이 입력만 */}
          {view !== null && total !== null && total >= CLIP_MIN_SECONDS && (
            <div>
              <ClipRangeBar value={barValue} total={total} view={view} currentSeconds={bar.currentSeconds} onChange={changeFromBar} />
              {bar.actions && <div className="mt-2 flex justify-end">{bar.actions(barValue)}</div>}
            </div>
          )}
          <div className="grid gap-4 sm:grid-cols-2">
            {/* 시작 위치는 끌어서도(슬라이더, 영상 전체 범위) 적어서도 고른다. 끌면 막대 손잡이처럼 미리보기 영상이 따라간다 */}
            <div className="text-sm font-medium text-primary">
              <label className="block">
                시작 위치
                <input
                  value={start}
                  onChange={(event) => changeStartText(event.target.value)}
                  onBlur={() => {
                    const seconds = parseClipTime(start)
                    if (seconds !== null) onStartCommitted?.(seconds)
                  }}
                  inputMode="numeric"
                  placeholder="0:00"
                  aria-invalid={rangeError !== ''}
                  aria-describedby="clip-range-hint"
                  className={`mt-2 ${inputClassName}`}
                />
              </label>
              {startMax !== null && startMax > 0 && (
                <input
                  type="range"
                  min={0}
                  max={startMax}
                  step={1}
                  value={Math.min(barStart, startMax)}
                  onChange={(event) => changeFromBar({ start: Number(event.target.value), duration }, 'start')}
                  aria-label="시작 위치 끌기"
                  aria-valuetext={formatDuration(Math.min(barStart, startMax))}
                  className="mt-3 block w-full accent-[var(--color-accent)]"
                />
              )}
            </div>
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
    </>
  )
}
