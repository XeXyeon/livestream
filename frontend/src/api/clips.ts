import { apiFetch, toQueryString } from './client'
import type { ClipDetail, ClipSummary, ClipUpdateRequest, LiveClipRequest, Page, RecordingClipRequest, VideoToken } from './types'

// §4.16 클립 행(CLIP-1). 만들기는 202 + ClipDetail(PROCESSING), 이후 GET /clips/{id}로 상태를 본다

export const createRecordingClip = (recordingId: number, body: RecordingClipRequest) =>
  apiFetch<ClipDetail>(`/recordings/${recordingId}/clips`, { method: 'POST', body: JSON.stringify(body) })

export const createLiveClip = (broadcastId: number, body: LiveClipRequest) =>
  apiFetch<ClipDetail>(`/broadcasts/${broadcastId}/clips`, { method: 'POST', body: JSON.stringify(body) })

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
