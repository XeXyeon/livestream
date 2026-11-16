import { useState } from 'react'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AlertDialog } from '@astryxdesign/core/AlertDialog'
import { Badge } from '@astryxdesign/core/Badge'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'
import { ApiError } from '../../api/client'
import { deleteClip, listMyClips } from '../../api/clips'
import type { ClipSummary } from '../../api/types'
import { formatDateTime, formatDuration } from '../broadcast/format'
import Spinner from '../common/Spinner'
import Thumbnail from '../common/Thumbnail'
import { CLIP_POLL_MS } from './useClipStatus'
import { CLIP_STATUS_BADGE } from './clipState'

/** S8 "내 클립"(§6.7, GET /members/me/clips): 만드는 중 · 실패 포함, 삭제. 만드는 중인 클립이 있으면 2초마다 새로 받는다 */
export default function MyClips() {
  const query = useInfiniteQuery({
    queryKey: ['clips', 'me'],
    queryFn: ({ pageParam }) => listMyClips(pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
    refetchInterval: (q) => (q.state.data?.pages.some((page) => page.items.some((clip) => clip.status === 'PROCESSING')) ? CLIP_POLL_MS : false),
  })
  const items = query.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <section aria-labelledby="my-clips" className="rounded-lg border border-border bg-surface p-5">
      <Heading level={2} id="my-clips">내 클립</Heading>
      {query.isPending && <Spinner label="클립을 불러오는 중" />}
      {query.isError && <div className="mt-3"><Banner role="alert" status="error" title="클립을 불러오지 못했습니다." /></div>}
      {query.isSuccess && items.length === 0 && <p className="mt-4 text-sm text-secondary">아직 만든 클립이 없습니다. 다시보기나 라이브에서 "클립"을 눌러 만들 수 있습니다.</p>}
      {items.length > 0 && (
        <ul className="mt-2 divide-y divide-border">
          {items.map((clip) => <MyClipRow key={clip.id} clip={clip} />)}
        </ul>
      )}
      {query.hasNextPage && (
        <div className="mt-4 flex justify-center">
          <Button label="더 보기" variant="secondary" isLoading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
        </div>
      )}
    </section>
  )
}

function MyClipRow({ clip }: { clip: ClipSummary }) {
  const queryClient = useQueryClient()
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState('')
  const badge = CLIP_STATUS_BADGE[clip.status]
  const titleId = `my-clip-${clip.id}`

  async function remove() {
    setDeleting(true)
    setError('')
    try {
      await deleteClip(clip.id)
      setConfirmOpen(false)
      queryClient.removeQueries({ queryKey: ['clip', clip.id] })
      await queryClient.invalidateQueries({ queryKey: ['clips'] })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '클립을 삭제하지 못했습니다.')
      setConfirmOpen(false)
    } finally {
      setDeleting(false)
    }
  }

  return (
    <li className="flex flex-col gap-4 py-4 lg:flex-row" aria-labelledby={titleId}>
      <div className="w-full shrink-0 lg:w-44"><Thumbnail src={clip.thumbnailUrl} alt="" /></div>
      <div className="min-w-0 flex-1 space-y-2">
        <p id={titleId} className="break-words font-semibold text-primary">
          {clip.status === 'READY'
            ? <Link to={`/clips/${clip.id}`} className="hover:text-accent focus-visible:outline-2 focus-visible:outline-accent">{clip.title}</Link>
            : clip.title}
        </p>
        <p className="flex flex-wrap items-center gap-2 text-sm text-secondary">
          <Badge variant={badge.variant} label={badge.label} />
          {clip.visibility === 'PRIVATE' && <Badge variant="warning" label="비공개" />}
          <span>{clip.channel.name}</span>
          <span>{formatDuration(clip.durationSeconds)}</span>
          <span>{formatDateTime(clip.createdAt)}</span>
        </p>
        {error && <p role="alert" className="text-sm text-error">{error}</p>}
      </div>
      <div className="flex shrink-0 gap-2 lg:flex-col">
        {/* 만드는 중에는 처리 결과와 엇갈리지 않게 삭제를 막는다 */}
        <Button label="삭제" variant="destructive" size="sm" isDisabled={clip.status === 'PROCESSING'} onClick={() => setConfirmOpen(true)} />
      </div>
      <AlertDialog
        cancelLabel="취소"
        isOpen={confirmOpen}
        onOpenChange={setConfirmOpen}
        title="클립을 삭제할까요?"
        description="클립 영상이 지워지며 되돌릴 수 없습니다. 원본 다시보기는 그대로 남습니다."
        actionLabel="삭제"
        actionVariant="destructive"
        isActionLoading={deleting}
        onAction={() => void remove()}
      />
    </li>
  )
}
