import { describe, expect, it, vi } from 'vitest'
import type { IFrame, IMessage, StompConfig } from '@stomp/stompjs'
import { brokerUrl, connectBroadcast } from './stomp'
import type { StompConnection } from './stomp'

class FakeClient {
  connected = false
  connectHeaders: Record<string, string> = {}
  activated = false
  deactivated = false
  subscriptions = new Map<string, (message: IMessage) => void>()
  published: { destination: string; body: string }[] = []
  readonly config: StompConfig

  constructor(config: StompConfig) {
    this.config = config
  }

  activate() { this.activated = true }
  deactivate() { this.deactivated = true; this.connected = false }
  subscribe(destination: string, callback: (message: IMessage) => void) {
    this.subscriptions.set(destination, callback)
    return { id: destination, unsubscribe: () => this.subscriptions.delete(destination) }
  }
  publish(params: { destination: string; body: string }) { this.published.push(params) }

  /** 브라우저 쪽 연결 한 번: beforeConnect → onConnect */
  async connect() {
    this.subscriptions.clear()
    await this.config.beforeConnect?.(this as never)
    this.connected = true
    this.config.onConnect?.({} as IFrame)
  }
  reject(code: string) {
    this.config.onStompError?.({ headers: { message: code } } as unknown as IFrame)
    this.drop()
  }
  drop() {
    this.connected = false
    this.config.onWebSocketClose?.({} as CloseEvent)
  }
  deliver(destination: string, body: unknown) {
    this.subscriptions.get(destination)?.({ body: typeof body === 'string' ? body : JSON.stringify(body) } as IMessage)
  }
}

function setup(tokens: (string | null)[] = ['t1']) {
  let client!: FakeClient
  const getToken = vi.fn((force: boolean) => {
    void force
    return Promise.resolve(tokens.length > 1 ? tokens.shift()! : tokens[0])
  })
  const messages: unknown[] = []
  const errors: unknown[] = []
  const states: StompConnection[] = []
  const conn = connectBroadcast(42, {
    onMessage: (m) => messages.push(m),
    onChatError: (e) => errors.push(e),
    onConnection: (s) => states.push(s),
    getToken,
    createClient: (config) => (client = new FakeClient(config)),
    url: 'ws://test/ws',
  })
  return { client, conn, getToken, messages, errors, states }
}

describe('connectBroadcast', () => {
  it('연결 직전에 토큰을 받아 Bearer 헤더로 넣고, 방송 토픽과 개인 에러 큐를 구독한다', async () => {
    const { client, getToken, states } = setup(['t1'])
    expect(client.activated).toBe(true)
    await client.connect()
    expect(getToken).toHaveBeenCalledWith(false)
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer t1' })
    expect([...client.subscriptions.keys()]).toEqual(['/topic/broadcasts/42', '/user/queue/errors'])
    expect(states).toEqual(['connecting', 'connected'])
  })

  it('비로그인은 헤더 없이 연결하고 개인 에러 큐는 구독하지 않는다', async () => {
    const { client } = setup([null])
    await client.connect()
    expect(client.connectHeaders).toEqual({})
    expect([...client.subscriptions.keys()]).toEqual(['/topic/broadcasts/42'])
  })

  it('토픽 메시지와 전송 실패를 넘기고, 깨진 본문은 버린다', async () => {
    const { client, messages, errors } = setup()
    await client.connect()
    client.deliver('/topic/broadcasts/42', { type: 'viewers', count: 3 })
    client.deliver('/topic/broadcasts/42', 'not json')
    client.deliver('/user/queue/errors', { code: 'BROADCAST_NOT_LIVE', message: '방송 중이 아닙니다' })
    expect(messages).toEqual([{ type: 'viewers', count: 3 }])
    expect(errors).toEqual([{ code: 'BROADCAST_NOT_LIVE', message: '방송 중이 아닙니다' }])
  })

  it('CONNECT가 TOKEN_EXPIRED로 거절되면 다음 연결 직전에 강제로 refresh한다(익명으로 떨어지지 않음)', async () => {
    const { client, getToken, states } = setup(['old', 'new'])
    await client.connect()
    client.reject('TOKEN_EXPIRED')
    expect(states.at(-1)).toBe('reconnecting')
    await client.connect()
    expect(getToken).toHaveBeenLastCalledWith(true)
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer new' })
    // 그다음 재연결은 다시 보통 방식
    client.drop()
    await client.connect()
    expect(getToken).toHaveBeenLastCalledWith(false)
  })

  it('처음 연결 전에 끊기면 connecting, 연결된 적이 있으면 reconnecting', async () => {
    const { client, states } = setup()
    client.drop()
    expect(states.at(-1)).toBe('connecting')
    await client.connect()
    client.drop()
    expect(states.at(-1)).toBe('reconnecting')
  })

  it('sendChat은 연결돼 있을 때만 /app/broadcasts/{id}/chat로 보낸다', async () => {
    const { client, conn } = setup()
    expect(conn.sendChat('hi')).toBe(false)
    await client.connect()
    expect(conn.sendChat('안녕')).toBe(true)
    expect(client.published).toEqual([{ destination: '/app/broadcasts/42/chat', body: JSON.stringify({ content: '안녕' }) }])
  })

  it('close 뒤에는 연결을 끊고 상태를 알리지 않으며 보내지 않는다', async () => {
    const { client, conn, states } = setup()
    await client.connect()
    conn.close()
    expect(client.deactivated).toBe(true)
    const count = states.length
    client.drop()
    expect(states).toHaveLength(count)
    expect(conn.sendChat('x')).toBe(false)
  })

  it('close 뒤 아직 열린 소켓으로 온 메시지 · 에러는 버린다', async () => {
    const { client, conn, messages, errors } = setup()
    await client.connect()
    conn.close()
    client.deliver('/topic/broadcasts/42', { type: 'viewers', count: 1 })
    client.deliver('/user/queue/errors', { code: 'X', message: 'x' })
    expect(messages).toEqual([])
    expect(errors).toEqual([])
  })

  it('익명 연결에서는 보내지 않는다', async () => {
    const { client, conn } = setup([null])
    await client.connect()
    expect(conn.sendChat('hi')).toBe(false)
    expect(client.published).toEqual([])
  })
})

describe('brokerUrl', () => {
  it('https면 wss, 아니면 ws로 같은 호스트의 /ws', () => {
    expect(brokerUrl({ protocol: 'https:', host: 'example.com' })).toBe('wss://example.com/ws')
    expect(brokerUrl({ protocol: 'http:', host: 'localhost:5173' })).toBe('ws://localhost:5173/ws')
  })
})
