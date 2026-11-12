import { apiFetch, toQueryString } from './client'
import type {
  BroadcastDetail, BroadcastStatus, BroadcastSummary, BroadcastUpdateInput, CategoryCode, Page, Recommendations,
} from './types'

export type BroadcastListQuery = { status?: Extract<BroadcastStatus, 'LIVE' | 'DISCONNECTED'>; category?: CategoryCode; page?: number; size?: number }

export const listBroadcasts = ({ status = 'LIVE', category, page = 0, size = 20 }: BroadcastListQuery = {}) =>
  apiFetch<Page<BroadcastSummary>>(`/broadcasts${toQueryString({ status, category, page, size })}`)

export const getBroadcast = (id: number) => apiFetch<BroadcastDetail>(`/broadcasts/${id}`)

export const getRecommendations = (id: number) => apiFetch<Recommendations>(`/broadcasts/${id}/recommendations`)

export const updateBroadcast = (id: number, input: BroadcastUpdateInput) =>
  apiFetch<BroadcastDetail>(`/broadcasts/${id}`, { method: 'PATCH', body: JSON.stringify(input) })

export const endBroadcast = (id: number) => apiFetch<void>(`/broadcasts/${id}/end`, { method: 'POST' })
