import { useState } from 'react'
import type { CSSProperties, RefObject } from 'react'

// WHEP · 그리드 공용: 소리 있는 자동 재생을 먼저 시도하고 막히면 음소거 재생 + "소리 켜기",
// 세로 영상(모바일 송출)이면 세로 비율로 보여 준다.
export function useVideoSurface(videoRef: RefObject<HTMLVideoElement | null>) {
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

  const containerClass = `relative mx-auto w-full overflow-hidden bg-black ${portrait ? 'max-h-[80vh]' : 'aspect-video'}`
  const containerStyle: CSSProperties | undefined = portrait
    ? { aspectRatio: `${portrait.w} / ${portrait.h}`, maxWidth: `calc(80vh * ${portrait.w / portrait.h})` }
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
