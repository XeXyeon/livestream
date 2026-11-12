import { useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Button } from '@astryxdesign/core/Button'
import { followChannel, unfollowChannel } from '../../api/follows'
import { ApiError } from '../../api/client'
import type { BroadcastDetail, ChannelResponse, FollowStatus } from '../../api/types'
import { useAuth } from '../../store/auth'
import { followView } from './followView'
import { MY_FOLLOWS_KEY, useMyFollows } from './useMyFollows'

type Props = {
  channelId: number
  /** 상세 응답 값. 팔로우 여부는 내 팔로우 목록을 기준으로 해서 버튼 판단에는 쓰지 않는다(followView) */
  isFollowing: boolean
  /** 상세 응답 값(응답을 받으면 상세 캐시를 고쳐 이 값이 바뀐다) */
  followerCount: number
  isOwner: boolean
}

/**
 * 팔로우 버튼(S2 채널 링크 옆 · S9 헤더). "팔로우"/"팔로잉" + 팔로워 수. 자기 채널이면 수만 보인다.
 * 비로그인은 로그인 화면으로 보낸 뒤 이 화면으로 돌아온다. 응답을 받으면 상세 캐시를 고치고 사이드바 목록을 다시 부른다.
 * 채널이 바뀌면 상태가 넘어가지 않게 부르는 쪽에서 key={channelId}를 준다.
 */
export default function FollowButton({ channelId, followerCount, isOwner }: Props) {
  const location = useLocation()
  const queryClient = useQueryClient()
  const memberId = useAuth((state) => state.member?.id ?? null)
  const myChannelId = useAuth((state) => state.member?.channelId ?? null)
  const follows = useMyFollows()
  // 마지막 응답(이 회원 · 이 채널). 목록을 다시 받는 동안 버튼이 이전 상태로 돌아가지 않게 한다
  const [result, setResult] = useState<{ memberId: number; status: FollowStatus; at: number } | null>(null)

  const mutation = useMutation({
    mutationFn: (follow: boolean) => (follow ? followChannel(channelId) : unfollowChannel(channelId)),
    onSuccess: (status) => {
      if (memberId !== null) setResult({ memberId, status, at: Date.now() })
      // 렌더 시점의 channelId가 아니라 응답의 채널로 고친다
      const target = status.channelId
      const patch = { followerCount: status.followerCount, isFollowing: status.following }
      queryClient.setQueryData<ChannelResponse>(['channel', target], (old) => (old ? { ...old, ...patch } : old))
      queryClient.setQueriesData<BroadcastDetail>({ queryKey: ['broadcast'] }, (old) =>
        old && old.channel?.id === target ? { ...old, ...patch } : old,
      )
      void queryClient.invalidateQueries({ queryKey: MY_FOLLOWS_KEY })
    },
  })

  const latest = result && result.memberId === memberId && result.status.channelId === channelId
    ? { following: result.status.following, at: result.at }
    : null
  const view = followView({
    isMine: isOwner || myChannelId === channelId,
    ready: follows.ready,
    loggedIn: follows.loggedIn,
    list: follows.data
      ? { status: 'success', has: follows.data.some((item) => item.channelId === channelId), updatedAt: follows.dataUpdatedAt }
      : { status: follows.isError ? 'error' : 'pending' },
    latest,
  })
  const countText = <span className="text-sm text-secondary">팔로워 {followerCount.toLocaleString('ko-KR')}</span>

  if (view.kind === 'count') return countText

  let button
  if (view.kind === 'login') {
    button = <Button label="팔로우" variant="primary" size="sm" href={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} />
  } else if (view.kind === 'loading') {
    button = <Button label="팔로우" variant="primary" size="sm" isLoading />
  } else {
    // 문구가 상태를 말하므로 aria-pressed는 두지 않는다
    button = (
      <Button
        label={view.following ? '팔로잉' : '팔로우'}
        variant={view.following ? 'secondary' : 'primary'}
        size="sm"
        isLoading={mutation.isPending}
        onClick={() => mutation.mutate(!view.following)}
      />
    )
  }

  return (
    <span className="inline-flex flex-wrap items-center gap-2">
      {button}
      {countText}
      {mutation.isError && (
        <span role="alert" className="text-xs text-error">
          {mutation.error instanceof ApiError ? mutation.error.message : '요청을 처리하지 못했습니다.'}
        </span>
      )}
    </span>
  )
}
