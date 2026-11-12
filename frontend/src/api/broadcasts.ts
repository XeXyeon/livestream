import { apiFetch, toQueryString } from './client'
import type {
  BroadcastDetail, BroadcastStatus, BroadcastSummary, BroadcastUpdateInput, CategoryCode, MyBroadcastItem, Page, Recommendations,
} from './types'

export type BroadcastListQuery = { status?: Extract<BroadcastStatus, 'LIVE' | 'DISCONNECTED'>; category?: CategoryCode; page?: number; size?: number }

export const listBroadcasts = ({ status = 'LIVE', category, page = 0, size = 20 }: BroadcastListQuery = {}) =>
  apiFetch<Page<BroadcastSummary>>(`/broadcasts${toQueryString({ status, category, page, size })}`)

export const getBroadcast = (id: number) => apiFetch<BroadcastDetail>(`/broadcasts/${id}`)

export const getRecommendations = (id: number) => apiFetch<Recommendations>(`/broadcasts/${id}/recommendations`)

export const updateBroadcast = (id: number, input: BroadcastUpdateInput) =>
  apiFetch<BroadcastDetail>(`/broadcasts/${id}`, { method: 'PATCH', body: JSON.stringify(input) })

export const endBroadcast = (id: number) => apiFetch<void>(`/broadcasts/${id}/end`, { method: 'POST' })

/** 방송 삭제(주인, 204). 진행 중이면 서버가 먼저 종료하고, 다시보기 파일도 지운다 */
export const deleteBroadcast = (id: number) => apiFetch<void>(`/broadcasts/${id}`, { method: 'DELETE' })

/** 내 방송 기록(삭제 제외, 최신순) */
export const listMyBroadcasts = (page = 0, size = 20) =>
  apiFetch<Page<MyBroadcastItem>>(`/members/me/broadcasts${toQueryString({ page, size })}`)
