import { apiFetch, toQueryString } from './client'
import type { Page, RecordingDetail, RecordingSummary, VideoToken, Visibility } from './types'

export const listRecordings = (page = 0, size = 20) =>
  apiFetch<Page<RecordingSummary>>(`/recordings${toQueryString({ page, size, sort: 'recent' })}`)

export const getRecording = (id: number) => apiFetch<RecordingDetail>(`/recordings/${id}`)

/** 공개 설정(주인). 같은 값이면 서버는 아무것도 하지 않고 200 */
export const updateRecordingVisibility = (id: number, visibility: Visibility) =>
  apiFetch<RecordingDetail>(`/recordings/${id}`, { method: 'PATCH', body: JSON.stringify({ visibility }) })

/** 비공개 재생 토큰(주인, READY일 때). 5분 뒤 탐색이 실패하면 다시 받는다 */
export const createVideoToken = (id: number) =>
  apiFetch<VideoToken>(`/recordings/${id}/video-token`, { method: 'POST' })
