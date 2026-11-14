import { apiFetch } from './client'
import type { FollowedChannel, FollowStatus } from './types'

export const followChannel = (channelId: number) =>
  apiFetch<FollowStatus>(`/channels/${channelId}/follow`, { method: 'POST' })

export const unfollowChannel = (channelId: number) =>
  apiFetch<FollowStatus>(`/channels/${channelId}/follow`, { method: 'DELETE' })

/** 내가 팔로우한 채널 전체(페이징 없음) */
export const listMyFollows = () => apiFetch<FollowedChannel[]>('/members/me/follows')
