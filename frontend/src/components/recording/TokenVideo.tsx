import { useCallback, useEffect, useRef, useState } from 'react'
import type { RefObject, SyntheticEvent } from 'react'
import { ApiError } from '../../api/client'
import type { VideoToken } from '../../api/types'
import UnmuteButton from '../player/UnmuteButton'
import type { RecordingPlayback } from './recordingState'

const MAX_TOKEN_RETRIES = 3

type Props = {
  /** 바뀌면 영상 요소를 새로 만든다(다른 영상) */
  mediaKey: string
  playback: RecordingPlayback
  /** playback이 public일 때 재생 주소 */
  videoUrl: string | null
  poster: string | null
  /** playback이 private일 때 5분짜리 재생 주소를 받는다 */
  fetchToken: () => Promise<VideoToken>
  /** playback별 안내 문구(영상 자리에 겹쳐 보인다) */
  overlayText: Record<RecordingPlayback, string>
  /** 처음 불러올 때 이 위치(초)부터 */
  startAt?: number
  /** 소리 없이 자동 재생하고 "소리 켜기" 버튼을 띄운다(클립) */
  autoPlayMuted?: boolean
  /** 바깥에서 재생 위치를 읽을 때(클립 만들기의 시작 위치) */
  videoRef?: RefObject<HTMLVideoElement | null>
  /** 영상 이벤트를 바깥에서도 받을 때(클립 만들기: 구간 막대 · 영상 연동) */
  mediaEvents?: MediaEvents
}

export type MediaEvents = {
  onPause?: () => void
  onSeeked?: () => void
  onTimeUpdate?: () => void
}

/**
 * 파일 영상(다시보기 · 클립). 공개는 videoUrl, 비공개는 5분짜리 video-token 주소로 재생한다.
 * 토큰이 만료된 뒤 탐색 · 이어받기가 403으로 실패하면 새 토큰을 받아 같은 위치부터 이어 간다(§4.10).
 */
export default function TokenVideo({ mediaKey, playback, videoUrl, poster, fetchToken, overlayText, startAt, autoPlayMuted, videoRef, mediaEvents }: Props) {
  const [tokenUrl, setTokenUrl] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [muted, setMuted] = useState(Boolean(autoPlayMuted))
  const [playing, setPlaying] = useState(false)
  const resumeAt = useRef<{ time: number; play: boolean } | null>(startAt ? { time: startAt, play: false } : null)
  const retries = useRef(0)
  const request = useRef(0)
  const ownRef = useRef<HTMLVideoElement | null>(null)

  const loadToken = useCallback(async () => {
    const current = ++request.current
    try {
      const token = await fetchToken()
      if (current === request.current) { setTokenUrl(token.videoUrl); setError('') }
    } catch (cause) {
      if (current === request.current) setError(cause instanceof ApiError ? cause.message : '재생 주소를 받지 못했습니다.')
    }
  }, [fetchToken])

  useEffect(() => {
    retries.current = 0
    if (playback === 'private') void loadToken()
    else { request.current++; setTokenUrl(null) }
  }, [playback, loadToken])

  const src = playback === 'public' ? videoUrl : playback === 'private' ? tokenUrl : null

  function handleError(event: SyntheticEvent<HTMLVideoElement>) {
    const video = event.currentTarget
    if (playback === 'private' && retries.current < MAX_TOKEN_RETRIES) {
      retries.current++
      resumeAt.current = { time: video.currentTime, play: !video.paused }
      void loadToken()
      return
    }
    setError('영상을 재생하지 못했습니다. 잠시 뒤 새로고침해 주세요.')
  }

  function handleLoadedMetadata(event: SyntheticEvent<HTMLVideoElement>) {
    const target = resumeAt.current
    if (!target) return
    resumeAt.current = null
    const video = event.currentTarget
    video.currentTime = target.time
    if (target.play) void video.play().catch(() => {})
  }

  function setRefs(element: HTMLVideoElement | null) {
    ownRef.current = element
    // React는 muted를 속성으로 남기지 않아 자동 재생이 막힐 수 있다. 요소에 직접 둔다
    if (element && autoPlayMuted && !element.dataset.mutedInit) { element.muted = true; element.dataset.mutedInit = '1' }
    if (videoRef) videoRef.current = element
  }

  function unmute() {
    const video = ownRef.current
    if (!video) return
    video.muted = false
    setMuted(false)
  }

  return (
    <div className="relative aspect-video overflow-hidden rounded-lg bg-black">
      {src && (
        <video
          key={mediaKey}
          ref={setRefs}
          src={src}
          controls
          playsInline
          autoPlay={autoPlayMuted}
          muted={autoPlayMuted}
          preload="metadata"
          poster={poster ?? undefined}
          onError={handleError}
          onLoadedMetadata={handleLoadedMetadata}
          onPlaying={() => { retries.current = 0; setPlaying(true) }}
          onPause={() => { setPlaying(false); mediaEvents?.onPause?.() }}
          onSeeked={mediaEvents?.onSeeked}
          onTimeUpdate={mediaEvents?.onTimeUpdate}
          onVolumeChange={(event) => setMuted(event.currentTarget.muted)}
          className="h-full w-full"
        />
      )}
      {autoPlayMuted && muted && playing && <UnmuteButton onClick={unmute} />}
      {(!src || error) && (
        <div className="absolute inset-0 flex items-center justify-center p-6 text-center text-white">
          <p role={error ? 'alert' : 'status'} className="rounded-lg bg-black/70 px-4 py-3">
            {error || overlayText[playback]}
          </p>
        </div>
      )}
    </div>
  )
}
