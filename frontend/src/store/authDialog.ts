import { create } from 'zustand'

export type AuthDialogMode = 'login' | 'signup'

type AuthDialogState = {
  /** null이면 닫힘 */
  mode: AuthDialogMode | null
  /** 가입을 마치고 로그인 단계로 넘어왔을 때(안내 문구 · 이메일 채우기) */
  signedUpEmail: string | null
  open: (mode?: AuthDialogMode) => void
  switchTo: (mode: AuthDialogMode, signedUpEmail?: string | null) => void
  close: () => void
}

/**
 * 로그인 · 가입 모달. 페이지를 옮기지 않고 지금 화면 위에 띄운다(헤더 · "로그인하고 …" 버튼).
 * 로그인하면 모달만 닫고 그 화면에 머문다. 직접 주소(/login · /signup)와 보호 경로 리다이렉트는 페이지가 맡는다.
 */
export const useAuthDialog = create<AuthDialogState>((set) => ({
  mode: null,
  signedUpEmail: null,
  open: (mode = 'login') => set({ mode, signedUpEmail: null }),
  switchTo: (mode, signedUpEmail = null) => set({ mode, signedUpEmail }),
  close: () => set({ mode: null, signedUpEmail: null }),
}))
