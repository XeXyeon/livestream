import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from './client'
import { useAuth } from '../store/auth'

const auth = { accessToken: 'new-token', member: { id: 1, nickname: '방송인', channelId: 2 } }
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json' },
})

describe('apiFetch 401 갱신', () => {
  beforeEach(() => {
    useAuth.getState().setAuth({ ...auth, accessToken: 'old-token' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    useAuth.getState().clear()
  })

  it('동시 401에 refresh를 한 번만 호출하고 각 요청을 재시도한다', async () => {
    let refreshCount = 0
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/auth/refresh') {
        refreshCount++
        await new Promise((resolve) => setTimeout(resolve, 10))
        return json(200, auth)
      }
      const token = new Headers(init?.headers).get('Authorization')
      return token === 'Bearer new-token'
        ? json(200, { ok: true })
        : json(401, { code: 'TOKEN_EXPIRED', message: '만료' })
    })
    vi.stubGlobal('fetch', fetchMock)

    const results = await Promise.all([apiFetch('/first'), apiFetch('/second')])

    expect(results).toEqual([{ ok: true }, { ok: true }])
    expect(refreshCount).toBe(1)
    expect(fetchMock).toHaveBeenCalledTimes(5)
  })

  it('재시도에서도 401이면 로그인 상태를 지운다', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) =>
      input === '/api/auth/refresh'
        ? json(200, auth)
        : json(401, { code: 'UNAUTHENTICATED', message: '로그인이 필요합니다' }),
    ))

    await expect(apiFetch('/private')).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })
    expect(useAuth.getState().member).toBeNull()
  })
})
