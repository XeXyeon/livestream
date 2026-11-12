import { useState } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { EmptyState } from '@astryxdesign/core/EmptyState'
import { MessageSquare } from 'lucide-react'
import { listChannelPosts, writePost } from '../../api/posts'
import { formatRelative } from '../broadcast/format'
import Spinner from '../common/Spinner'
import PostEditor from '../post/PostEditor'

/** S9 커뮤니티 탭: 게시글 목록(최신순), 채널 주인은 글쓰기 */
export default function ChannelPosts({ channelId, isOwner }: { channelId: number; isOwner: boolean }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [writing, setWriting] = useState(false)
  const query = useInfiniteQuery({
    queryKey: ['channel', channelId, 'posts'],
    queryFn: ({ pageParam }) => listChannelPosts(channelId, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
  })
  const items = query.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <div className="space-y-4">
      {isOwner && !writing && (
        <div className="flex justify-end"><Button label="글쓰기" variant="primary" onClick={() => setWriting(true)} /></div>
      )}
      {writing && (
        <PostEditor
          submitLabel="올리기"
          onCancel={() => setWriting(false)}
          onSubmit={async (input) => {
            const post = await writePost(input)
            await queryClient.invalidateQueries({ queryKey: ['channel', channelId] })
            navigate(`/posts/${post.id}`)
          }}
        />
      )}
      {query.isPending && <Spinner label="게시글을 불러오는 중" />}
      {query.isError && <Banner role="alert" status="error" title="게시글을 불러오지 못했습니다." />}
      {query.isSuccess && items.length === 0 && (
        <div className="rounded-lg border border-dashed border-border py-6"><EmptyState title="아직 게시글이 없습니다." icon={<MessageSquare aria-hidden className="h-8 w-8" />} /></div>
      )}
      {items.length > 0 && (
        <ul className="divide-y divide-border rounded-lg border border-border bg-surface">
          {items.map((post) => (
            <li key={post.id}>
              <Link to={`/posts/${post.id}`} className="group block px-5 py-4 focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-accent">
                <p className="break-words font-semibold text-primary group-hover:text-accent">{post.title}</p>
                <p className="mt-1 line-clamp-2 break-words text-sm text-secondary">{post.preview}</p>
                <p className="mt-1 text-xs text-secondary">{formatRelative(post.createdAt)} · 댓글 {post.commentCount.toLocaleString('ko-KR')}</p>
              </Link>
            </li>
          ))}
        </ul>
      )}
      {query.hasNextPage && (
        <div className="flex justify-center">
          <Button label="더 보기" variant="secondary" isLoading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
        </div>
      )}
    </div>
  )
}
