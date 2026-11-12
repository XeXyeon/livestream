import { useState } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation } from 'react-router-dom'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'
import { deleteComment, listComments, updateComment, writeComment } from '../../api/comments'
import type { CommentTarget } from '../../api/comments'
import { useAuth } from '../../store/auth'
import Spinner from '../common/Spinner'
import CommentForm from './CommentForm'
import CommentItem from './CommentItem'
import { buildCommentTree } from './commentTree'

type Props = {
  target: CommentTarget
  /** 전체 댓글 수(상세 응답의 commentCount) */
  count: number
  /** 쓰기를 막는 이유(예: 다시보기 준비 전). 있으면 입력칸 대신 이 문구 */
  closedReason?: string
}

/** 다시보기(S6) · 게시글(S10) 댓글. 평면 목록을 2단 트리로 보여 주고, 작성 · 답글 · 수정 · 삭제 뒤 목록을 새로 받는다 */
export default function CommentList({ target, count, closedReason }: Props) {
  const queryClient = useQueryClient()
  const location = useLocation()
  const member = useAuth((state) => state.member)
  const ready = useAuth((state) => state.ready)
  const [replyTo, setReplyTo] = useState<number | null>(null)
  const queryKey = ['comments', target.kind, target.id]
  const query = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam }) => listComments(target, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
  })
  const tree = buildCommentTree(query.data?.pages.flatMap((page) => page.items) ?? [])
  const canWrite = !closedReason && member != null

  // 댓글 수가 상세 응답에 있으므로 상세도 함께 새로 받는다
  const refresh = () => Promise.all([
    queryClient.invalidateQueries({ queryKey }),
    queryClient.invalidateQueries({ queryKey: [target.kind, target.id] }),
  ])
  // 목록이 오래된 순이라 새 댓글은 마지막 페이지에 붙는다. 다 받지 않은 상태면 끝까지 받아 방금 쓴 댓글이 보이게 한다
  const write = async (content: string, parentId?: number) => {
    await writeComment(target, { content, parentId })
    await refresh()
    // refresh가 받은 최신 데이터 기준으로 다음 페이지가 없을 때까지 이어 받는다(없으면 fetchNextPage는 아무것도 하지 않는다)
    let result = await query.fetchNextPage()
    while (result.hasNextPage && !result.isError) result = await result.fetchNextPage()
  }
  const update = async (id: number, content: string) => { await updateComment(id, content); await refresh() }
  const remove = async (id: number) => { await deleteComment(id); await refresh() }

  return (
    <section aria-labelledby="comments-heading" className="space-y-4">
      <Heading level={2} id="comments-heading">댓글 {count.toLocaleString('ko-KR')}</Heading>
      {closedReason ? (
        <p className="rounded-lg border border-border bg-surface px-4 py-3 text-sm text-secondary">{closedReason}</p>
      ) : canWrite ? (
        <CommentForm label="댓글을 입력하세요" submitLabel="댓글 쓰기" onSubmit={(content) => write(content)} />
      ) : ready && (
        <Button href={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} label="로그인하고 댓글 쓰기" variant="secondary" />
      )}
      {query.isPending && <Spinner label="댓글을 불러오는 중" />}
      {query.isError && <Banner role="alert" status="error" title="댓글을 불러오지 못했습니다." />}
      {query.isSuccess && tree.length === 0 && <p className="text-sm text-secondary">아직 댓글이 없습니다.</p>}
      {tree.length > 0 && (
        <ul className="divide-y divide-border">
          {tree.map((node) => (
            <li key={node.id} className="py-2">
              <CommentItem
                comment={node}
                memberId={member?.id ?? null}
                onReply={canWrite && !node.deleted && node.parentId == null ? () => setReplyTo(replyTo === node.id ? null : node.id) : undefined}
                onUpdate={update}
                onDelete={remove}
              />
              {(node.replies.length > 0 || replyTo === node.id) && (
                <ul className="ml-4 border-l-2 border-border pl-4 sm:ml-6">
                  {node.replies.map((reply) => (
                    <li key={reply.id}>
                      <CommentItem comment={reply} memberId={member?.id ?? null} onUpdate={update} onDelete={remove} />
                    </li>
                  ))}
                  {replyTo === node.id && (
                    <li className="py-2">
                      <CommentForm
                        label={`${node.author.nickname}님에게 답글`}
                        submitLabel="답글 쓰기"
                        autoFocus
                        onSubmit={async (content) => { await write(content, node.id); setReplyTo(null) }}
                        onCancel={() => setReplyTo(null)}
                      />
                    </li>
                  )}
                </ul>
              )}
            </li>
          ))}
        </ul>
      )}
      {query.hasNextPage && (
        <div className="flex justify-center">
          <Button label="댓글 더 보기" variant="secondary" isLoading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
        </div>
      )}
    </section>
  )
}
