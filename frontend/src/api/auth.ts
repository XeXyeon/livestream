import { apiFetch } from './client'
import type { AuthResponse, SignupResponse } from './types'

export type LoginInput = { email: string; password: string }
export type SignupInput = LoginInput & { nickname: string }

export const login = (input: LoginInput) => apiFetch<AuthResponse>('/auth/login', {
  method: 'POST', body: JSON.stringify(input),
})

export const signup = (input: SignupInput) => apiFetch<SignupResponse>('/auth/signup', {
  method: 'POST', body: JSON.stringify(input),
})

export const logout = () => apiFetch<void>('/auth/logout', { method: 'POST' })
