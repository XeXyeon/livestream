import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { ApiErrorResponse, BroadcastDetail, DonationMessage } from '../../api/types'
import { useAuth } from '../../store/auth'
import { connectBroadcast } from '../../ws/stomp'
import type { StompConnection } from '../../ws/stomp'
import { applyChatMessage } from './chatState'
import type { ChatEntry } from './chatState'

/**
 * 방송 하나의 STOMP 토픽(§4.14 · §6.6): 채팅 목록을 모으고, status · viewers는 ['broadcast', id] 쿼리 캐시에 바로 반영한다
 * (S2 · S3가 같은 키를 읽으므로 폴링을 기다리지 않는다). 연결될 때마다 방송 상세를 다시 받아 끊긴 사이의 상태 변화를 메운다.
 * 로그인한 사람이 바뀌면(로그인 · 로그아웃) 연결을 새로 연다. 토큰 갱신만으로는 다시 연결하지 않는다.
 * 앱 시작 때 로그인 복구(bootstrap)가 끝나기 전에는 연결하지 않는다(익명으로 붙었다가 다시 붙는 것을 막는다).
 * onDonation: 실시간으로 온 후원만 알린다(입장 · 재연결 때의 history는 알리지 않는다) — 배너 · 스튜디오 알림(FE-6).
 */
export function useBroadcastChat(broadcastId: number | null, { onDonation }: { onDonation?: (donation: DonationMessage) => void } = {}) {
  const queryClient = useQueryClient()
  const memberId = useAuth((state) => state.member?.id ?? null)
  const authReady = useAuth((state) => state.ready)
  const [entries, setEntries] = useState<ChatEntry[]>([])
  const [connection, setConnection] = useState<StompConnection>('connecting')
  const [chatError, setChatError] = useState<ApiErrorResponse | null>(null)
  const connectionRef = useRef<ReturnType<typeof connectBroadcast> | null>(null)
  const [prevId, setPrevId] = useState(broadcastId)
  // 콜백이 바뀌어도 다시 연결하지 않는다
  const onDonationRef = useRef(onDonation)
  useEffect(() => { onDonationRef.current = onDonation }, [onDonation])

  // 다른 방송으로 바뀌면 목록을 비운다(렌더 중 조정: effect 안에서 setState를 피한다)
  if (prevId !== broadcastId) {
    setPrevId(broadcastId)
    setEntries([])
    setChatError(null)
    setConnection('connecting')
  }

  useEffect(() => {
    if (broadcastId === null || !authReady) return
    const key = ['broadcast', broadcastId]
    const patch = (change: Partial<BroadcastDetail>) =>
      queryClient.setQueryData<BroadcastDetail>(key, (old) => (old ? { ...old, ...change } : old))

    const conn = connectBroadcast(broadcastId, {
      onMessage: (message) => {
        if (message.type === 'status') {
          patch({ status: message.status })
          void queryClient.invalidateQueries({ queryKey: key, exact: true })
        } else if (message.type === 'viewers') {
          patch({ viewerCount: message.count })
        } else {
          setEntries((old) => applyChatMessage(old, message))
          if (message.type === 'donation') onDonationRef.current?.(message)
        }
      },
      onChatError: setChatError,
      onConnection: (state) => {
        setConnection(state)
        // exact: ['broadcast', id, 'recommendations'] 같은 하위 키까지 다시 받지 않는다
        if (state === 'connected') void queryClient.invalidateQueries({ queryKey: key, exact: true })
      },
    })
    connectionRef.current = conn
    return () => {
      conn.close()
      if (connectionRef.current === conn) connectionRef.current = null
    }
  }, [broadcastId, memberId, authReady, queryClient])

  const send = useCallback((content: string) => {
    setChatError(null)
    return connectionRef.current?.sendChat(content.trim()) ?? false
  }, [])

  return { entries, connection, chatError, send, clearError: () => setChatError(null) }
}
