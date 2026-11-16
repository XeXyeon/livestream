import { apiFetch, toQueryString } from './client'
import type { ClipDetail, ClipDraftDetail, ClipRequest, ClipSummary, ClipUpdateRequest, Page, VideoToken } from './types'

// §4.16 클립 행(CLIP-1). 만들기는 202 + ClipDetail(PROCESSING), 이후 GET /clips/{id}로 상태를 본다

export const createRecordingClip = (recordingId: number, body: ClipRequest) =>
  apiFetch<ClipDetail>(`/recordings/${recordingId}/clips`, { method: 'POST', body: JSON.stringify(body) })

// 라이브 클립 초안(CLIP-3, §4.18 라이브 클립 초안): 최근 2분을 받아 둔 뒤 그 안에서 구간을 골라 만든다

/** 초안 만들기(본문 없음) → 202 + PENDING. 이전 내 초안은 서버가 지운다 */
export const createLiveDraft = (broadcastId: number) =>
  apiFetch<ClipDraftDetail>(`/broadcasts/${broadcastId}/clip-drafts`, { method: 'POST' })

/** 만든 회원만. READY면 videoUrl에 재생 토큰(10분) */
export const getDraft = (id: number) => apiFetch<ClipDraftDetail>(`/clip-drafts/${id}`)

/** 초안에서 클립. startSeconds는 초안 기준(서버가 방송 기준으로 바꿔 저장) */
export const createClipFromDraft = (id: number, body: ClipRequest) =>
  apiFetch<ClipDetail>(`/clip-drafts/${id}/clips`, { method: 'POST', body: JSON.stringify(body) })

export const getClip = (id: number) => apiFetch<ClipDetail>(`/clips/${id}`)

/** READY · PUBLIC 최신순(채널 주인이 부르면 비공개 포함) */
export const listChannelClips = (channelId: number, page = 0, size = 12) =>
  apiFetch<Page<ClipSummary>>(`/channels/${channelId}/clips${toQueryString({ page, size })}`)

export const listRecordingClips = (recordingId: number, page = 0, size = 8) =>
  apiFetch<Page<ClipSummary>>(`/recordings/${recordingId}/clips${toQueryString({ page, size })}`)

/** 내가 만든 클립 전부(PROCESSING · FAILED 포함) */
export const listMyClips = (page = 0, size = 12) =>
  apiFetch<Page<ClipSummary>>(`/members/me/clips${toQueryString({ page, size })}`)

/** 제목 · 공개 설정(만든 회원 · 채널 주인) */
export const updateClip = (id: number, body: ClipUpdateRequest) =>
  apiFetch<ClipDetail>(`/clips/${id}`, { method: 'PATCH', body: JSON.stringify(body) })

export const deleteClip = (id: number) => apiFetch<void>(`/clips/${id}`, { method: 'DELETE' })

/** 비공개 재생 토큰(만든 회원 · 채널 주인, READY일 때). 5분 */
export const createClipVideoToken = (id: number) =>
  apiFetch<VideoToken>(`/clips/${id}/video-token`, { method: 'POST' })
