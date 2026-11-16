import { useSyncExternalStore } from 'react'

/** CSS 미디어 쿼리가 맞는지(창 크기가 바뀌면 다시 그린다) */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (onChange) => {
      const list = window.matchMedia(query)
      list.addEventListener('change', onChange)
      return () => list.removeEventListener('change', onChange)
    },
    () => window.matchMedia(query).matches,
    // 서버 렌더(테스트의 renderToString)에는 창이 없으므로 "안 맞음"으로 본다
    () => false,
  )
}

/** 지금 문서가 전체 화면인지 */
export function useIsFullscreen(): boolean {
  return useSyncExternalStore(
    (onChange) => {
      document.addEventListener('fullscreenchange', onChange)
      return () => document.removeEventListener('fullscreenchange', onChange)
    },
    () => document.fullscreenElement !== null,
  )
}
