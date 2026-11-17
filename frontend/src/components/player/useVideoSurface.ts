import { useState } from 'react'
import type { CSSProperties, RefObject } from 'react'

// WHEP · 그리드 공용: 소리 있는 자동 재생을 먼저 시도하고 막히면 음소거 재생 + "소리 켜기",
// 세로 영상(모바일 송출)이면 세로 비율로 보여 준다.
// fill: 영화관 모드 · 전체 화면에서 부모 높이를 채운다(비율 고정 대신 object-contain).
export function useVideoSurface(videoRef: RefObject<HTMLVideoElement | null>, fill = false) {
  const [muted, setMuted] = useState(false)
  const [portrait, setPortrait] = useState<{ w: number; h: number } | null>(null)

  function tryPlay() {
    const video = videoRef.current
    if (!video) return
    video.play().catch(() => {
      video.muted = true
      setMuted(true)
      void video.play().catch(() => undefined)
    })
  }

  function handleResize() {
    const video = videoRef.current
    if (!video || !video.videoWidth) return
    setPortrait(video.videoHeight > video.videoWidth ? { w: video.videoWidth, h: video.videoHeight } : null)
  }

  function unmute() {
    const video = videoRef.current
    if (!video) return
    video.muted = false
    setMuted(false)
    void video.play().catch(() => undefined)
  }

  // group: 컨트롤(VideoControls)이 마우스를 올렸을 때 보이도록
  const containerClass = fill
    ? 'group relative h-full w-full overflow-hidden bg-black'
    // 세로 영상: lg 미만(휴대폰)은 화면 높이의 38%만 쓰는 검은 칸에 레터박스로 넣어 아래 채팅이 화면의 1/3쯤 보이게 하고,
    // lg 이상은 영상 비율대로(높이 80vh까지)
    : `group relative mx-auto w-full overflow-hidden bg-black ${portrait
      ? 'h-[38dvh] lg:h-auto lg:max-h-[80vh] lg:max-w-[calc(80vh*var(--pr))] lg:aspect-[var(--pa)]'
      : 'aspect-video'}`
  const containerStyle: CSSProperties | undefined = portrait && !fill
    ? ({ '--pa': `${portrait.w} / ${portrait.h}`, '--pr': portrait.w / portrait.h } as CSSProperties)
    : undefined

  return {
    muted,
    unmute,
    containerClass,
    containerStyle,
    videoProps: {
      onLoadedMetadata: () => { handleResize(); tryPlay() },
      onResize: handleResize,
    },
  }
}
