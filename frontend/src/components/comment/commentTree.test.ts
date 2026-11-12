import { describe, expect, it } from 'vitest'
import type { Comment } from '../../api/types'
import { buildCommentTree, isEdited, validateComment } from './commentTree'

const at = '2026-11-12T00:00:00Z'
const comment = (id: number, parentId: number | null = null): Comment => ({
  id, parentId, content: `c${id}`, deleted: false, author: { id: 1, nickname: 'a', isChannelOwner: false }, createdAt: at, updatedAt: at,
})

describe('buildCommentTree', () => {
  it('대댓글을 부모 아래에 순서대로 모은다', () => {
    const tree = buildCommentTree([comment(1), comment(2), comment(3, 1), comment(4, 2), comment(5, 1)])
    expect(tree.map((node) => node.id)).toEqual([1, 2])
    expect(tree[0].replies.map((reply) => reply.id)).toEqual([3, 5])
    expect(tree[1].replies.map((reply) => reply.id)).toEqual([4])
  })
  it('부모가 없는 대댓글은 최상위에 둔다', () => {
    const tree = buildCommentTree([comment(1), comment(7, 99)])
    expect(tree.map((node) => node.id)).toEqual([1, 7])
  })
  it('같은 id가 두 번 오면 한 번만', () => {
    const tree = buildCommentTree([comment(1), comment(2, 1), comment(2, 1), comment(1)])
    expect(tree).toHaveLength(1)
    expect(tree[0].replies).toHaveLength(1)
  })
  it('삭제된 부모도 자리를 지킨다', () => {
    const tree = buildCommentTree([{ ...comment(1), deleted: true, content: null }, comment(2, 1)])
    expect(tree[0].deleted).toBe(true)
    expect(tree[0].replies).toHaveLength(1)
  })
})

describe('validateComment', () => {
  it('공백뿐이면 거절, 500자 초과 거절', () => {
    expect(validateComment('  ')).not.toBe('')
    expect(validateComment('a'.repeat(501))).not.toBe('')
    expect(validateComment(` ${'a'.repeat(500)} `)).toBe('')
    // 이모지는 UTF-16 2칸이지만 서버(code point) 기준 1자
    expect(validateComment('😀'.repeat(500))).toBe('')
  })
})

describe('isEdited', () => {
  it('1초 넘게 차이 나면 수정됨', () => {
    expect(isEdited({ createdAt: at, updatedAt: at })).toBe(false)
    expect(isEdited({ createdAt: at, updatedAt: '2026-11-12T00:05:00Z' })).toBe(true)
  })
})
