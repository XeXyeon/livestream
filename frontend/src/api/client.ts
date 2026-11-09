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
