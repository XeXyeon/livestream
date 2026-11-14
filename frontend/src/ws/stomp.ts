// §6.6 STOMP 클라이언트. 방송 하나의 토픽을 구독하고 채팅을 보낸다.
// - 연결(재연결 포함) 직전마다 ensureFreshToken()으로 토큰을 받아 CONNECT 헤더에 넣는다. 토큰이 없으면 익명.
// - 서버가 CONNECT를 TOKEN_EXPIRED · TOKEN_INVALID로 거절하면(ERROR 프레임, 연결 종료) 다음 연결 직전에 강제로 refresh한다.
//   refresh가 실패하면 로그아웃 상태가 되고 그때만 익명으로 붙는다.
// - 로그인 상태면 `/user/queue/errors`도 구독해 채팅 전송 실패({code, message})를 받는다(연결은 유지됨).
// 로그인 상태가 바뀌었을 때의 재연결은 이 연결을 닫고 새로 만드는 쪽(useBroadcastChat)이 맡는다.
import { Client } from '@stomp/stompjs'
import type { IFrame, IMessage, StompConfig, StompSubscription } from '@stomp/stompjs'
import { ensureFreshToken, forceRefreshToken } from '../api/client'
import type { ApiErrorResponse, BroadcastTopicMessage } from '../api/types'

export const STOMP_RECONNECT_MS = 3000
const TOKEN_REJECTED = new Set(['TOKEN_EXPIRED', 'TOKEN_INVALID'])

export type StompConnection = 'connecting' | 'connected' | 'reconnecting'

type ClientLike = {
  activate: () => void
  deactivate: () => Promise<void> | void
  subscribe: (destination: string, callback: (message: IMessage) => void) => StompSubscription
  publish: (params: { destination: string; body: string }) => void
  connected: boolean
  connectHeaders: Record<string, string>
}

type Options = {
  onMessage: (message: BroadcastTopicMessage) => void
  onChatError?: (error: ApiErrorResponse) => void
  onConnection?: (state: StompConnection) => void
  getToken?: (force: boolean) => Promise<string | null>
  createClient?: (config: StompConfig) => ClientLike
  url?: string
}

export function brokerUrl(location: Pick<Location, 'protocol' | 'host'> = window.location): string {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`
}

function parse<T>(message: IMessage): T | null {
  try {
    return JSON.parse(message.body) as T
  } catch {
    return null
  }
}

export function connectBroadcast(broadcastId: number, options: Options) {
  const getToken = options.getToken ?? ((force: boolean) => (force ? forceRefreshToken() : ensureFreshToken()))
  const createClient = options.createClient ?? ((config: StompConfig) => new Client(config))
  let forceRefresh = false
  let authenticated = false
  let everConnected = false
  let closed = false

  const client = createClient({
    brokerURL: options.url ?? brokerUrl(),
    reconnectDelay: STOMP_RECONNECT_MS,
    // 하트비트는 기본값(10초)
    beforeConnect: async (self) => {
      const token = await getToken(forceRefresh)
      forceRefresh = false
      authenticated = token !== null
      self.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {}
    },
    onConnect: () => {
      if (closed) return
      everConnected = true
      // deactivate()는 DISCONNECT 영수증을 기다리는 동안 소켓을 열어 두므로, 닫은 뒤 온 메시지는 버린다.
      client.subscribe(`/topic/broadcasts/${broadcastId}`, (frame) => {
        if (closed) return
        const message = parse<BroadcastTopicMessage>(frame)
        if (message && typeof message.type === 'string') options.onMessage(message)
      })
      if (authenticated) {
        client.subscribe('/user/queue/errors', (frame) => {
          if (closed) return
          const error = parse<ApiErrorResponse>(frame)
          if (error?.code) options.onChatError?.(error)
        })
      }
      options.onConnection?.('connected')
    },
    onStompError: (frame: IFrame) => {
      if (TOKEN_REJECTED.has(frame.headers.message)) forceRefresh = true
    },
    onWebSocketClose: () => {
      if (!closed) options.onConnection?.(everConnected ? 'reconnecting' : 'connecting')
    },
  })

  options.onConnection?.('connecting')
  client.activate()

  return {
    /** 연결돼 있지 않거나 익명 연결이면 보내지 않고 false(익명 SEND는 서버가 ERROR로 연결을 끊는다) */
    sendChat(content: string): boolean {
      if (closed || !client.connected || !authenticated) return false
      client.publish({ destination: `/app/broadcasts/${broadcastId}/chat`, body: JSON.stringify({ content }) })
      return true
    },
    close() {
      closed = true
      void client.deactivate()
    },
  }
}
