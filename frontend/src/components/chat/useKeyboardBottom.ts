import { useEffect } from 'react'
import type { RefObject } from 'react'

/**
 * 화면 아래에 붙은 `fixed bottom-0` 줄(시청 화면의 채팅 입력 줄)이 가상 키보드에 가려지지 않게 한다(§6.7 S2 시청 화면 정리 ②).
 * `active`(입력 포커스)인 동안 `window.visualViewport`의 `resize` · `scroll`에 맞춰 보이는 영역 아래 끝으로 `bottom`을 올리고,
 * 끝나면 되돌린다. `useKeyboardInset`(전체 화면 컨테이너용)과 같은 원리: iOS Safari는 키보드가 올라와도 레이아웃 뷰포트가
 * 줄지 않고 보이는 영역만 줄어든다. visualViewport가 없으면 아무것도 하지 않는다. DOM 스타일을 직접 만져 다시 그리지 않는다.
 */
export function useKeyboardBottom(ref: RefObject<HTMLElement | null>, active: boolean) {
  useEffect(() => {
    const viewport = typeof window === 'undefined' ? undefined : window.visualViewport
    const element = ref.current
    if (!active || !viewport || !element) return

    const apply = () => {
      const hidden = window.innerHeight - viewport.height - viewport.offsetTop
      element.style.bottom = `${Math.max(0, hidden)}px`
    }
    apply()
    viewport.addEventListener('resize', apply)
    viewport.addEventListener('scroll', apply)
    return () => {
      viewport.removeEventListener('resize', apply)
      viewport.removeEventListener('scroll', apply)
      element.style.removeProperty('bottom')
    }
  }, [ref, active])
}
