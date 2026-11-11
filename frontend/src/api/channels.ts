import { apiFetch, toQueryString } from './client'
import type { ChannelResponse, ChannelUpdateInput, MyChannelResponse, Page, RecordingSummary } from './types'

export const getChannel = (id: number) => apiFetch<ChannelResponse>(`/channels/${id}`)

export const getMyChannel = () => apiFetch<MyChannelResponse>('/channels/me')

export const updateMyChannel = (input: ChannelUpdateInput) =>
  apiFetch<MyChannelResponse>('/channels/me', { method: 'PATCH', body: JSON.stringify(input) })

export const rotateStreamKey = () =>
  apiFetch<{ streamKey: string }>('/channels/me/stream-key/rotate', { method: 'POST' })

export const rotateWidgetKey = () =>
  apiFetch<{ widgetKey: string; widgetUrl: string }>('/channels/me/widget-key/rotate', { method: 'POST' })

/** 채널의 공개 다시보기(ENDED · READY · PUBLIC) 최신순 */
export const listChannelRecordings = (id: number, page = 0, size = 20) =>
  apiFetch<Page<RecordingSummary>>(`/channels/${id}/broadcasts${toQueryString({ page, size })}`)
