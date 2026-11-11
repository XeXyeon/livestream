import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { endBroadcast, getBroadcast, updateBroadcast } from '../../api/broadcasts'
import { getMyChannel, updateMyChannel } from '../../api/channels'
import { ApiError, ensureFreshToken } from '../../api/client'
import type { CategoryCode } from '../../api/types'
import { WhipClient, WhipError } from '../../media/whip'
import type { WhipState } from '../../media/whip'

export type SessionPhase = 'idle' | 'starting' | 'live' | 'reconnecting' | 'ending' | 'ended' | 'error'
export type BroadcastInfo = { title: string; category: CategoryCode }

export const MY_CHANNEL_KEY = ['channel', 'me'] as const

const ACTIVE: WhipState[] = ['connecting', 'live', 'reconnecting']

/**
 * 송출 세션(§9 FE-2 · FE-3 공용).
 * 시작: PATCH /channels/me(방송 정보) → WhipClient.publish(whipUrl?src=…) → GET /channels/me 3초 폴링으로 currentBroadcast 확인.
 * 종료: whip.close() → stopMedia() → POST /broadcasts/{id}/end (송출을 먼저 끊어 자동 재시도가 돌지 않게 한다).
 * 페이지를 떠나면 송출만 끊고 end는 부르지 않는다(서버 유예 60초 안에 돌아와 다시 시작하면 같은 방송으로 이어진다).
 */
export function useBroadcastSession(src: 'pc' | 'mobile', options: { onServerEnded?: () => void } = {}) {
  const queryClient = useQueryClient()
  const whipRef = useRef<WhipClient | null>(null)
  const broadcastIdRef = useRef<number | null>(null)
  const onServerEndedRef = useRef(options.onServerEnded)
  const [whipState, setWhipState] = useState<WhipState>('idle')
  const [ending, setEnding] = useState(false)
  const [finished, setFinished] = useState(false)
  const [error, setError] = useState('')
  const active = ACTIVE.includes(whipState)

  useEffect(() => { onServerEndedRef.current = options.onServerEnded }, [options.onServerEnded])

  const channel = useQuery({
    queryKey: MY_CHANNEL_KEY,
    queryFn: getMyChannel,
    refetchInterval: active ? 3000 : false,
  })
  const current = active ? channel.data?.currentBroadcast ?? null : null
  if (current) broadcastIdRef.current = current.id

  const broadcast = useQuery({
    queryKey: ['broadcast', current?.id],
    queryFn: () => getBroadcast(current!.id),
    enabled: !!current,
    refetchInterval: 5000,
  })

  useEffect(() => () => {
    whipRef.current?.close()
    whipRef.current = null
  }, [])

  const start = useCallback(async (stream: MediaStream, info: BroadcastInfo) => {
    setError('')
    setFinished(false)
    broadcastIdRef.current = null
    whipRef.current?.close()
    setWhipState('connecting')
    try {
      await ensureFreshToken()
      const me = await updateMyChannel(info)
      queryClient.setQueryData(MY_CHANNEL_KEY, me)
      const whip = new WhipClient(`${me.publish.whipUrl}?src=${src}`, me.streamKey)
      whipRef.current = whip
      whip.onState((state) => {
        if (whipRef.current !== whip) return
        setWhipState(state)
        if (state === 'ended') {
          setError('서버에서 방송이 종료되었습니다.')
          onServerEndedRef.current?.()
        } else if (state === 'failed') {
          setError('송출 연결이 끊겨 다시 연결하지 못했습니다.')
        }
      })
      await whip.publish(stream)
    } catch (cause) {
      setWhipState('failed')
      setError(cause instanceof WhipError || cause instanceof ApiError ? cause.message : '송출을 시작하지 못했습니다.')
    }
  }, [queryClient, src])

  const end = useCallback(async (stopMedia: () => void) => {
    setEnding(true)
    setError('')
    let id = broadcastIdRef.current
    whipRef.current?.close()
    whipRef.current = null
    stopMedia()
    try {
      if (id === null) id = (await getMyChannel()).currentBroadcast?.id ?? null
      if (id !== null) await endBroadcast(id)
    } catch (cause) {
      if (!(cause instanceof ApiError && cause.code === 'BROADCAST_ALREADY_ENDED')) {
        setError(cause instanceof ApiError ? cause.message : '방송 종료를 서버에 알리지 못했습니다.')
      }
    } finally {
      setWhipState('idle')
      setEnding(false)
      setFinished(true)
      void queryClient.invalidateQueries({ queryKey: MY_CHANNEL_KEY })
    }
  }, [queryClient])

  /** 방송 정보 저장: 송출 중이면 PATCH /broadcasts/{id}(채널 방송 정보도 함께 바뀜), 아니면 PATCH /channels/me */
  const saveInfo = useCallback(async (info: BroadcastInfo) => {
    const id = broadcastIdRef.current
    if (active && id !== null) {
      await updateBroadcast(id, info)
      void queryClient.invalidateQueries({ queryKey: ['broadcast', id] })
    } else {
      queryClient.setQueryData(MY_CHANNEL_KEY, await updateMyChannel(info))
    }
  }, [active, queryClient])

  /** 마이크 · 카메라 트랙 교체(재협상 없음) */
  const replaceTrack = useCallback((kind: 'audio' | 'video', track: MediaStreamTrack) =>
    whipRef.current?.replaceTrack(kind, track) ?? Promise.resolve(), [])

  let phase: SessionPhase
  if (ending) phase = 'ending'
  else if (whipState === 'connecting') phase = 'starting'
  else if (whipState === 'live') phase = current?.status === 'LIVE' ? 'live' : 'starting'
  else if (whipState === 'reconnecting') phase = 'reconnecting'
  else if (whipState === 'failed') phase = 'error'
  else if (whipState === 'ended' || finished) phase = 'ended'
  else phase = 'idle'

  return {
    phase,
    active,
    error,
    channel,
    broadcastId: current?.id ?? null,
    viewerCount: broadcast.data?.viewerCount ?? null,
    start,
    end,
    saveInfo,
    replaceTrack,
  }
}
