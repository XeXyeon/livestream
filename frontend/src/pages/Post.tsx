import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AlertDialog } from '@astryxdesign/core/AlertDialog'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { Heading } from '@astryxdesign/core/Heading'
import { ApiError } from '../api/client'
import { deletePost, getPost, updatePost } from '../api/posts'
import CommentList from '../components/comment/CommentList'
import { isEdited } from '../components/comment/commentTree'
import Spinner from '../components/common/Spinner'
import PostEditor from '../components/post/PostEditor'
import { useAuth } from '../store/auth'

const date = (iso: string) => new Date(iso).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })

/** S10 커뮤니티 글(§6.3): 본문 · 주인 수정 · 삭제 · 댓글 */
export default function Post() {
  const postId = Number(useParams().postId)
  const valid = Number.isInteger(postId) && postId > 0
  const authReady = useAuth((state) => state.ready)
  const memberId = useAuth((state) => state.member?.id ?? 0)
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [editing, setEditing] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState('')
  // isOwner가 로그인 복구 뒤의 값이 되도록 복구가 끝난 뒤 요청한다
  const query = useQuery({ queryKey: ['post', postId, memberId], queryFn: () => getPost(postId), enabled: valid && authReady })

  if (!valid || (query.error instanceof ApiError && query.error.status === 404)) {
    return <EmptyState headingLevel={1} title="게시글을 찾을 수 없습니다" description="삭제되었거나 없는 주소입니다." />
  }
  if (query.isError) return <Banner role="alert" status="error" title="게시글을 불러오지 못했습니다." description="잠시 뒤 다시 시도해 주세요." />
  if (!query.data) return <Spinner label="게시글을 불러오는 중" />

  const post = query.data
  const channelPosts = `/channel/${post.channel.id}?tab=posts`

  async function remove() {
    setDeleting(true)
    setError('')
    try {
      await deletePost(post.id)
      // 먼저 떠나고 캐시를 지운다(지운 직후 다시 그려져 404를 다시 요청하지 않게)
      navigate(channelPosts, { replace: true })
      queryClient.removeQueries({ queryKey: ['post', post.id] })
      await queryClient.invalidateQueries({ queryKey: ['channel', post.channel.id] })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '게시글을 삭제하지 못했습니다.')
      setDeleting(false)
      setConfirmOpen(false)
    }
  }

  return (
    <div className="space-y-8">
      <Link to={channelPosts} className="text-sm text-secondary hover:text-accent focus-visible:outline-2 focus-visible:outline-accent">← {post.channel.name} 커뮤니티</Link>
      {editing ? (
        <PostEditor
          initial={{ title: post.title, content: post.content }}
          submitLabel="저장"
          onCancel={() => setEditing(false)}
          onSubmit={async (input) => {
            const updated = await updatePost(post.id, input)
            queryClient.setQueryData(['post', post.id, memberId], updated)
            await queryClient.invalidateQueries({ queryKey: ['channel', post.channel.id, 'posts'] })
            setEditing(false)
          }}
        />
      ) : (
        <article className="space-y-4 rounded-lg border border-border bg-surface p-5">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <Heading level={1}>{post.title}</Heading>
              <p className="mt-1 text-sm text-secondary">
                {post.channel.ownerNickname} · <time dateTime={post.createdAt}>{date(post.createdAt)}</time>{isEdited(post) && ' (수정됨)'}
              </p>
            </div>
            {post.isOwner && (
              <div className="flex gap-2">
                <Button label="수정" variant="secondary" size="sm" onClick={() => setEditing(true)} />
                <Button label="삭제" variant="destructive" size="sm" onClick={() => setConfirmOpen(true)} />
              </div>
            )}
          </div>
          <p className="whitespace-pre-line break-words text-primary">{post.content}</p>
        </article>
      )}
      {error && <Banner role="alert" status="error" title={error} />}
      <CommentList target={{ kind: 'post', id: post.id }} count={post.commentCount} />
      <AlertDialog
        isOpen={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="게시글을 삭제할까요?"
        description="댓글도 함께 지워지며 되돌릴 수 없습니다."
        actionLabel="삭제"
        actionVariant="destructive"
        isActionLoading={deleting}
        onAction={() => void remove()}
      />
    </div>
  )
}
