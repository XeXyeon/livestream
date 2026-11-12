import type { QueryClient } from '@tanstack/react-query'
import type { BroadcastDetail, BroadcastTopicMessage } from '../../api/types'

export const broadcastKey = (broadcastId: number) => ['broadcast', broadcastId] as const

/**
 * 방송 상태를 바꾸는 토픽 메시지(status · viewers · info)를 ['broadcast', id] 상세 캐시에 바로 반영한다.
 * 반영했으면 true, 채팅 · 후원 · history처럼 상세와 무관한 메시지면 false.
 * info(FE-5#1)는 제목 · 카테고리만 고치고 다시 받지 않는다. status는 종료 · 다시보기 정보가 바뀌므로 상세를 다시 받는다.
 */
export function applyTopicToCache(queryClient: QueryClient, broadcastId: number, message: BroadcastTopicMessage): boolean {
  const key = broadcastKey(broadcastId)
  const patch = (change: Partial<BroadcastDetail>) =>
    queryClient.setQueryData<BroadcastDetail>(key, (old) => (old ? { ...old, ...change } : old))
  switch (message.type) {
    case 'status':
      patch({ status: message.status })
      void queryClient.invalidateQueries({ queryKey: key, exact: true })
      return true
    case 'viewers':
      patch({ viewerCount: message.count })
      return true
    case 'info':
      patch({ title: message.title, category: message.category })
      return true
    default:
      return false
  }
}
