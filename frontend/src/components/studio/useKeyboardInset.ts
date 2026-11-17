import { useEffect } from 'react'
import type { RefObject } from 'react'

/**
 * 화면 전체를 덮는 컨테이너(`fixed inset-0`)가 가상 키보드에 가려지지 않게 한다(§6.7 S4 ③).
 * `active`(입력 포커스)인 동안 `window.visualViewport`의 `resize` · `scroll`에 맞춰 컨테이너의 height · top을
 * 보이는 영역(visualViewport.height · offsetTop)으로 두고, 끝나면 되돌린다.
 * iOS Safari는 키보드가 올라와도 레이아웃 뷰포트가 줄지 않고 보이는 영역만 줄며, Android Chrome은 레이아웃 뷰포트가 줄어든다 — 둘 다 같은 코드.
 * visualViewport가 없으면 아무것도 하지 않는다. 렌더를 다시 하지 않도록 DOM 스타일을 직접 만진다.
 */
export function useKeyboardInset(containerRef: RefObject<HTMLElement | null>, active: boolean) {
  useEffect(() => {
    const viewport = typeof window === 'undefined' ? undefined : window.visualViewport
    const container = containerRef.current
    if (!active || !viewport || !container) return

    const apply = () => {
      container.style.height = `${viewport.height}px`
      container.style.top = `${viewport.offsetTop}px`
    }
    apply()
    viewport.addEventListener('resize', apply)
    viewport.addEventListener('scroll', apply)
    return () => {
      viewport.removeEventListener('resize', apply)
      viewport.removeEventListener('scroll', apply)
      container.style.removeProperty('height')
      container.style.removeProperty('top')
    }
  }, [containerRef, active])
}
