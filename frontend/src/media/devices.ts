// §6.5 기기 감지: 화면 공유를 못 하거나 모바일 UA면 모바일 스튜디오로 보낸다.
export function isMobileDevice(nav: Navigator = navigator): boolean {
  return typeof nav.mediaDevices?.getDisplayMedia !== 'function' || /Android|iPhone|iPad|iPod|Mobile/i.test(nav.userAgent)
}

export type StudioTarget = 'pc' | 'mobile'

/** `?force=pc|mobile`이 있으면 그것을, 없으면 기기 감지 결과를 쓴다. */
export function studioTarget(force: string | null, nav: Navigator = navigator): StudioTarget {
  if (force === 'pc' || force === 'mobile') return force
  return isMobileDevice(nav) ? 'mobile' : 'pc'
}
