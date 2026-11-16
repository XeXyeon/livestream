import type { ClipDetail, ClipStatus } from '../../api/types'
import type { RecordingPlayback } from '../recording/recordingState'

// 클립 규칙(§4.16 · §4.18 · §6.7). 길이 범위는 서버 app.clip.min~max(5~60)와 같다
export const CLIP_MIN_SECONDS = 5
export const CLIP_MAX_SECONDS = 60
export const CLIP_DEFAULT_SECONDS = 30
export const CLIP_TITLE_MAX = 100

/** "m:ss" · "h:mm:ss" · 초 숫자 → 초. 형식이 틀리면 null */
export function parseClipTime(text: string): number | null {
  const value = text.trim()
  if (/^\d+$/.test(value)) return Number(value)
  const match = /^(?:(\d+):)?(\d{1,2}):(\d{2})$/.exec(value)
  if (!match) return null
  const [, hours, minutes, seconds] = match
  if (Number(seconds) >= 60 || (hours !== undefined && Number(minutes) >= 60)) return null
  return Number(hours ?? 0) * 3600 + Number(minutes) * 60 + Number(seconds)
}

export type ClipRangeResult = { ok: true; startSeconds: number } | { ok: false; message: string }

/**
 * 클립 구간 검사(서버 POST /recordings/{id}/clips · POST /clip-drafts/{id}/clips 와 같은 규칙). totalSeconds는 다시보기 길이
 * 또는 라이브 초안의 windowSeconds(초안 기준 시작). null이면(길이를 모르는 다시보기) 끝 검사는 서버에 맡긴다.
 */
export function validateClipRange(startText: string, durationSeconds: number, totalSeconds: number | null): ClipRangeResult {
  const startSeconds = parseClipTime(startText)
  if (startSeconds === null) return { ok: false, message: '시작 위치는 분:초(예: 1:05) 형식으로 적어 주세요.' }
  if (!Number.isInteger(durationSeconds) || durationSeconds < CLIP_MIN_SECONDS || durationSeconds > CLIP_MAX_SECONDS) {
    return { ok: false, message: `길이는 ${CLIP_MIN_SECONDS}~${CLIP_MAX_SECONDS}초입니다.` }
  }
  if (totalSeconds !== null && startSeconds + durationSeconds > totalSeconds) {
    return { ok: false, message: '클립 구간이 영상 범위를 벗어났습니다.' }
  }
  return { ok: true, startSeconds }
}

/** 지금 재생 위치를 클립 시작 기본값으로: 남은 길이가 기본 길이보다 짧으면 끝에서 기본 길이만큼 앞으로 당긴다 */
export function defaultClipStart(currentSeconds: number, totalSeconds: number | null, durationSeconds = CLIP_DEFAULT_SECONDS): number {
  const now = Math.max(0, Math.floor(currentSeconds))
  if (totalSeconds === null) return now
  return Math.max(0, Math.min(now, Math.floor(totalSeconds) - durationSeconds))
}

/** 기본 길이: 30초, 영상(초안)이 그보다 짧으면 그 길이(최소 5초 — 그보다 짧으면 검사가 막는다) */
export function defaultClipDuration(totalSeconds: number | null): number {
  if (totalSeconds === null) return CLIP_DEFAULT_SECONDS
  return Math.max(CLIP_MIN_SECONDS, Math.min(CLIP_DEFAULT_SECONDS, Math.floor(totalSeconds)))
}

/** 라이브 초안의 기본 시작: 끝(= 클립 버튼을 누른 순간)에서 기본 길이만큼 앞, `max(0, windowSeconds − 길이)` */
export function defaultLiveClipStart(windowSeconds: number, durationSeconds = defaultClipDuration(windowSeconds)): number {
  return Math.max(0, Math.floor(windowSeconds) - durationSeconds)
}

/** 클립 화면에서 무엇을 보여 줄지(다시보기와 같은 TokenVideo 규칙) */
export function clipPlayback(clip: Pick<ClipDetail, 'status' | 'visibility' | 'videoUrl' | 'isOwner' | 'isCreator'>): RecordingPlayback {
  switch (clip.status) {
    case 'PROCESSING':
      return 'processing'
    case 'FAILED':
      return 'failed'
    case 'READY':
      if (clip.videoUrl) return 'public'
      if (clip.visibility === 'PRIVATE' && (clip.isOwner || clip.isCreator)) return 'private'
      return 'unavailable'
  }
}

export const CLIP_STATUS_BADGE: Record<ClipStatus, { label: string; variant: 'info' | 'error' | 'success' }> = {
  PROCESSING: { label: '만드는 중', variant: 'info' },
  READY: { label: '완료', variant: 'success' },
  FAILED: { label: '실패', variant: 'error' },
}
