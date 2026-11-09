import { create } from 'zustand'
import type { AuthResponse, Member } from '../api/types'

type AuthState = {
  accessToken: string | null
  member: Member | null
  ready: boolean
  setAuth: (auth: AuthResponse) => void
  clear: () => void
  finishBootstrap: () => void
}

export const useAuth = create<AuthState>((set) => ({
  accessToken: null,
  member: null,
  ready: false,
  setAuth: ({ accessToken, member }) => set({ accessToken, member }),
  clear: () => set({ accessToken: null, member: null }),
  finishBootstrap: () => set({ ready: true }),
}))
