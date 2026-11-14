import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch, ensureFreshToken, shouldRetryQuery, tokenExpiresAt } from './client'
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

describe('ensureFreshToken', () => {
  const jwt = (exp: number) => `h.${btoa(JSON.stringify({ sub: '1', exp })).replace(/=+$/, '')}.s`
  const now = Date.parse('2026-11-11T00:00:00Z')

  afterEach(() => {
    vi.unstubAllGlobals()
    useAuth.getState().clear()
  })

  it('만료까지 60초보다 많이 남았으면 refresh하지 않는다', async () => {
    const token = jwt(now / 1000 + 120)
    useAuth.getState().setAuth({ ...auth, accessToken: token })
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await expect(ensureFreshToken(now)).resolves.toBe(token)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('60초 안에 만료되면 refresh한 새 토큰을 준다', async () => {
    useAuth.getState().setAuth({ ...auth, accessToken: jwt(now / 1000 + 30) })
    vi.stubGlobal('fetch', vi.fn(async () => json(200, auth)))
    await expect(ensureFreshToken(now)).resolves.toBe('new-token')
    expect(useAuth.getState().accessToken).toBe('new-token')
  })

  it('refresh가 실패하면 null을 주고 로그인 상태를 지운다', async () => {
    useAuth.getState().setAuth({ ...auth, accessToken: jwt(now / 1000 - 10) })
    vi.stubGlobal('fetch', vi.fn(async () => json(401, { code: 'TOKEN_INVALID', message: 'x' })))
    await expect(ensureFreshToken(now)).resolves.toBeNull()
    expect(useAuth.getState().member).toBeNull()
  })

  it('로그인 상태가 아니면 null', async () => {
    await expect(ensureFreshToken(now)).resolves.toBeNull()
  })

  it('exp를 읽는다', () => {
    expect(tokenExpiresAt(jwt(100))).toBe(100_000)
    expect(tokenExpiresAt('broken')).toBeNull()
  })
})

describe('shouldRetryQuery', () => {
  it('4xx는 재시도하지 않는다', () => {
    expect(shouldRetryQuery(0, new ApiError(404, 'CHANNEL_NOT_FOUND', '채널을 찾을 수 없습니다.'))).toBe(false)
    expect(shouldRetryQuery(0, new ApiError(403, 'FORBIDDEN', '권한이 없습니다.'))).toBe(false)
  })

  it('5xx와 네트워크 오류는 3번까지 재시도한다', () => {
    expect(shouldRetryQuery(0, new ApiError(503, 'UNAVAILABLE', '잠시 후 다시 시도'))).toBe(true)
    expect(shouldRetryQuery(2, new TypeError('Failed to fetch'))).toBe(true)
    expect(shouldRetryQuery(3, new TypeError('Failed to fetch'))).toBe(false)
  })
})
