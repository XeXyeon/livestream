import { apiFetch, toQueryString } from './client'
import type { Page, Post, PostSummary, PostWriteInput } from './types'

export const listChannelPosts = (channelId: number, page = 0, size = 20) =>
  apiFetch<Page<PostSummary>>(`/channels/${channelId}/posts${toQueryString({ page, size })}`)

/** 내 채널에 글쓰기(201) */
export const writePost = (input: PostWriteInput) =>
  apiFetch<Post>('/channels/me/posts', { method: 'POST', body: JSON.stringify(input) })

export const getPost = (id: number) => apiFetch<Post>(`/posts/${id}`)

export const updatePost = (id: number, input: Partial<PostWriteInput>) =>
  apiFetch<Post>(`/posts/${id}`, { method: 'PATCH', body: JSON.stringify(input) })

/** 물리 삭제, 댓글도 함께 지워진다(204) */
export const deletePost = (id: number) => apiFetch<void>(`/posts/${id}`, { method: 'DELETE' })
