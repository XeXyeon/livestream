import { useState } from 'react'
import { AlertDialog } from '@astryxdesign/core/AlertDialog'
import { Badge } from '@astryxdesign/core/Badge'
import { Button } from '@astryxdesign/core/Button'
import { ApiError } from '../../api/client'
import type { Comment } from '../../api/types'
import { formatRelative } from '../broadcast/format'
import CommentForm from './CommentForm'
import { isEdited } from './commentTree'

type Props = {
  comment: Comment
  memberId: number | null
  /** 답글 버튼(최상위 댓글 · 쓰기 가능 · 로그인일 때만) */
  onReply?: () => void
  onUpdate: (id: number, content: string) => Promise<unknown>
  onDelete: (id: number) => Promise<unknown>
}

/** 댓글 한 개. 수정 · 삭제 버튼은 작성자에게만(최종 판단은 서버) */
export default function CommentItem({ comment, memberId, onReply, onUpdate, onDelete }: Props) {
  const [editing, setEditing] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState('')
  const mine = !comment.deleted && memberId === comment.author.id

  async function remove() {
    setDeleting(true)
    setError('')
    try {
      await onDelete(comment.id)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '댓글을 삭제하지 못했습니다.')
    } finally {
      setDeleting(false)
      setConfirmOpen(false)
    }
  }

  if (comment.deleted) {
    return <p className="py-2 text-sm italic text-secondary">삭제된 댓글입니다</p>
  }

  return (
    <div className="py-2">
      <p className="flex flex-wrap items-center gap-2 text-sm">
        <span className={`font-semibold ${comment.author.isChannelOwner ? 'text-accent' : 'text-primary'}`}>{comment.author.nickname}</span>
        {comment.author.isChannelOwner && <Badge variant="info" label="채널 주인" />}
        <time dateTime={comment.createdAt} className="text-xs text-secondary">{formatRelative(comment.createdAt)}</time>
        {isEdited(comment) && <span className="text-xs text-secondary">(수정됨)</span>}
      </p>
      {editing ? (
        <div className="mt-2">
          <CommentForm
            label="댓글 수정"
            submitLabel="저장"
            initial={comment.content ?? ''}
            autoFocus
            onSubmit={async (content) => { await onUpdate(comment.id, content); setEditing(false) }}
            onCancel={() => setEditing(false)}
          />
        </div>
      ) : (
        <p className="mt-1 whitespace-pre-line break-words text-primary">{comment.content}</p>
      )}
      {!editing && (onReply || mine) && (
        <div className="mt-1 flex flex-wrap gap-1">
          {onReply && <Button label="답글" variant="ghost" size="sm" onClick={onReply} />}
          {mine && <Button label="수정" variant="ghost" size="sm" onClick={() => setEditing(true)} />}
          {mine && <Button label="삭제" variant="ghost" size="sm" onClick={() => setConfirmOpen(true)} />}
        </div>
      )}
      {error && <p role="alert" className="mt-1 text-sm text-error">{error}</p>}
      <AlertDialog
        cancelLabel="취소"
        isOpen={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="댓글을 삭제할까요?"
        description="삭제한 댓글은 되돌릴 수 없습니다."
        actionLabel="삭제"
        actionVariant="destructive"
        isActionLoading={deleting}
        onAction={() => void remove()}
      />
    </div>
  )
}
