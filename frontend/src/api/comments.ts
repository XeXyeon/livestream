import { apiFetch, toQueryString } from './client'
import type { Comment, CommentWriteInput, Page } from './types'

/** 댓글 대상: 다시보기 또는 게시글 */
export type CommentTarget = { kind: 'recording' | 'post'; id: number }

const base = ({ kind, id }: CommentTarget) => (kind === 'recording' ? `/recordings/${id}/comments` : `/posts/${id}/comments`)

export const listComments = (target: CommentTarget, page = 0, size = 50) =>
  apiFetch<Page<Comment>>(`${base(target)}${toQueryString({ page, size })}`)

export const writeComment = (target: CommentTarget, input: CommentWriteInput) =>
  apiFetch<Comment>(base(target), { method: 'POST', body: JSON.stringify(input) })

export const updateComment = (id: number, content: string) =>
  apiFetch<Comment>(`/comments/${id}`, { method: 'PATCH', body: JSON.stringify({ content }) })

export const deleteComment = (id: number) => apiFetch<void>(`/comments/${id}`, { method: 'DELETE' })
