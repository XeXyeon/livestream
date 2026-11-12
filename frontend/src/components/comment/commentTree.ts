import type { Comment } from '../../api/types'

export type CommentNode = Comment & { replies: Comment[] }

/**
 * 평면 목록(createdAt 오름차순)을 2단 트리로(§9 FE-8). 대댓글은 부모 아래에 원래 순서대로.
 * 부모를 찾지 못한 대댓글(목록 경계 등 드문 경우)은 잃지 않도록 최상위에 둔다.
 */
export function buildCommentTree(input: readonly Comment[]): CommentNode[] {
  // 페이지 사이에 목록이 바뀌면(offset 방식) 같은 댓글이 두 번 올 수 있어 id로 거른다
  const seen = new Set<number>()
  const comments = input.filter((comment) => !seen.has(comment.id) && seen.add(comment.id))
  const roots: CommentNode[] = []
  const byId = new Map<number, CommentNode>()
  for (const comment of comments) {
    if (comment.parentId == null) {
      const node = { ...comment, replies: [] }
      roots.push(node)
      byId.set(comment.id, node)
    }
  }
  for (const comment of comments) {
    if (comment.parentId == null) continue
    const parent = byId.get(comment.parentId)
    if (parent) parent.replies.push(comment)
    else roots.push({ ...comment, replies: [] })
  }
  return roots
}

/** 서버처럼 글자 수를 code point로 센다(이모지 1개 = 1자) */
export const countChars = (value: string) => [...value].length

/** 댓글 본문 검사(서버 규칙: 앞뒤 공백 제외 1~500자). 통과하면 빈 문자열 */
export const COMMENT_MAX = 500
export function validateComment(content: string): string {
  const trimmed = content.trim()
  if (!trimmed) return '댓글 내용을 입력해 주세요.'
  if (countChars(trimmed) > COMMENT_MAX) return `댓글은 ${COMMENT_MAX}자까지 쓸 수 있습니다.`
  return ''
}

/** 수정된 댓글인지(작성 직후 저장 시각 차이는 무시) */
export const isEdited = (comment: Pick<Comment, 'createdAt' | 'updatedAt'>) =>
  Date.parse(comment.updatedAt) - Date.parse(comment.createdAt) > 1000
