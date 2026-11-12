import { useAuth } from '../store/auth'
import type { ApiErrorResponse, AuthResponse } from './types'

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

let refreshPromise: Promise<AuthResponse> | null = null

async function parseError(response: Response): Promise<ApiError> {
  const body = await response.json().catch(() => null) as ApiErrorResponse | null
  return new ApiError(response.status, body?.code ?? 'HTTP_ERROR', body?.message ?? '요청을 처리하지 못했습니다.')
}

async function refreshSession(): Promise<AuthResponse> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      const response = await fetch('/api/auth/refresh', {
        method: 'POST',
        credentials: 'same-origin',
      })
      if (!response.ok) throw await parseError(response)
      const auth = await response.json() as AuthResponse
      useAuth.getState().setAuth(auth)
      return auth
    })().finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

export async function bootstrapAuth(): Promise<void> {
  try {
    await refreshSession()
  } catch {
    useAuth.getState().clear()
  } finally {
    useAuth.getState().finishBootstrap()
  }
}

/** access JWT의 exp(ms). 해석하지 못하면 null */
export function tokenExpiresAt(token: string): number | null {
  try {
    const payload = token.split('.')[1]
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(payload.length / 4) * 4, '='))
    const exp = (JSON.parse(json) as { exp?: unknown }).exp
    return typeof exp === 'number' ? exp * 1000 : null
  } catch {
    return null
  }
}

/**
 * §6.4: access의 exp가 60초 안이면 먼저 refresh한다. STOMP 연결 직전 · 송출 시작 직전에 부른다.
 * 로그인 상태가 아니거나 refresh가 실패하면 null(실패 시 로그인 상태를 지운다).
 */
export async function ensureFreshToken(now: number = Date.now()): Promise<string | null> {
  const token = useAuth.getState().accessToken
  if (!token) return null
  const expiresAt = tokenExpiresAt(token)
  if (expiresAt !== null && expiresAt - now > 60_000) return token
  try {
    return (await refreshSession()).accessToken
  } catch {
    useAuth.getState().clear()
    return null
  }
}

/** 서버가 토큰을 만료로 거절했을 때(STOMP CONNECT `TOKEN_EXPIRED`) exp와 무관하게 refresh한다. 실패하면 로그아웃 상태로. */
export async function forceRefreshToken(): Promise<string | null> {
  if (!useAuth.getState().accessToken) return null
  try {
    return (await refreshSession()).accessToken
  } catch {
    useAuth.getState().clear()
    return null
  }
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const request = (token: string | null) => {
    const headers = new Headers(init.headers)
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    return fetch(`/api${path}`, { ...init, headers, credentials: 'same-origin' })
  }

  const originalToken = useAuth.getState().accessToken
  let response = await request(originalToken)
  if (response.status === 401 && !path.startsWith('/auth/')) {
    const error = await parseError(response)
    if (error.code === 'TOKEN_EXPIRED' || error.code === 'UNAUTHENTICATED') {
      try {
        // Another request may already have renewed the token while this request was in flight.
        const currentToken = useAuth.getState().accessToken
        const token = currentToken && currentToken !== originalToken
          ? currentToken
          : (await refreshSession()).accessToken
        response = await request(token)
        if (response.status === 401) useAuth.getState().clear()
      } catch {
        useAuth.getState().clear()
        throw error
      }
    } else {
      throw error
    }
  }

  if (!response.ok) throw await parseError(response)
  if (response.status === 204) return undefined as T
  const body = await response.text()
  return (body ? JSON.parse(body) : undefined) as T
}

/** 값이 없는 키는 뺀 `?a=1&b=2` (없으면 빈 문자열) */
export function toQueryString(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') search.set(key, String(value))
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

// TanStack Query 재시도 규칙: 4xx(없음 · 권한 · 입력 오류)는 다시 물어도 같으므로 바로 실패시키고,
// 네트워크 오류 · 5xx만 최대 3번 다시 시도한다.
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false
  return failureCount < 3
}
