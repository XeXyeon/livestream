import { create } from 'zustand'

type PlayerUiState = {
  /** 영화관 모드: 시청 화면에서 영상 · 채팅만 화면을 채운다(헤더는 남고 채널 사이드바는 숨김). lg 이상에서만 적용 */
  theater: boolean
  setTheater: (theater: boolean) => void
}

// Header(사이드바 숨김)와 Live(배치) · 플레이어 컨트롤(버튼)이 함께 본다
export const usePlayerUi = create<PlayerUiState>((set) => ({
  theater: false,
  setTheater: (theater) => set({ theater }),
}))
