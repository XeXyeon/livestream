import { useCallback, useEffect, useRef, useState } from 'react'
import type { SyntheticEvent } from 'react'
import { ApiError } from '../../api/client'
import { createVideoToken } from '../../api/recordings'
import type { RecordingDetail } from '../../api/types'
import { recordingPlayback } from './recordingState'

const MAX_TOKEN_RETRIES = 3

/**
 * S6 영상. 공개는 videoUrl, 비공개(주인)는 5분짜리 video-token 주소로 재생한다.
 * 토큰이 만료된 뒤 탐색 · 이어받기가 403으로 실패하면 새 토큰을 받아 같은 위치부터 이어 간다(§4.10).
 */
export default function RecordingVideo({ recording }: { recording: RecordingDetail }) {
  const playback = recordingPlayback(recording)
  const [tokenUrl, setTokenUrl] = useState<string | null>(null)
  const [error, setError] = useState('')
  const resumeAt = useRef<{ time: number; play: boolean } | null>(null)
  const retries = useRef(0)
  const request = useRef(0)

  const fetchToken = useCallback(async () => {
    const current = ++request.current
    try {
      const token = await createVideoToken(recording.id)
      if (current === request.current) { setTokenUrl(token.videoUrl); setError('') }
    } catch (cause) {
      if (current === request.current) setError(cause instanceof ApiError ? cause.message : '재생 주소를 받지 못했습니다.')
    }
  }, [recording.id])

  useEffect(() => {
    retries.current = 0
    resumeAt.current = null
    if (playback === 'private') void fetchToken()
    else { request.current++; setTokenUrl(null) }
  }, [playback, fetchToken])

  const src = playback === 'public' ? recording.videoUrl : playback === 'private' ? tokenUrl : null

  function handleError(event: SyntheticEvent<HTMLVideoElement>) {
    const video = event.currentTarget
    if (playback === 'private' && retries.current < MAX_TOKEN_RETRIES) {
      retries.current++
      resumeAt.current = { time: video.currentTime, play: !video.paused }
      void fetchToken()
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

  return (
    <div className="relative aspect-video overflow-hidden rounded-lg bg-black">
      {src && (
        <video
          key={recording.id}
          src={src}
          controls
          playsInline
          preload="metadata"
          poster={recording.thumbnailUrl ?? undefined}
          onError={handleError}
          onLoadedMetadata={handleLoadedMetadata}
          onPlaying={() => { retries.current = 0 }}
          className="h-full w-full"
        />
      )}
      {(!src || error) && (
        <div className="absolute inset-0 flex items-center justify-center p-6 text-center text-white">
          <p role={error ? 'alert' : 'status'} className="rounded-lg bg-black/70 px-4 py-3">
            {error || OVERLAY_TEXT[playback]}
          </p>
        </div>
      )}
    </div>
  )
}

const OVERLAY_TEXT: Record<ReturnType<typeof recordingPlayback>, string> = {
  public: '영상을 불러오는 중…',
  private: '재생 주소를 받는 중…',
  unavailable: '지금은 영상을 재생할 수 없습니다.',
  processing: '다시보기를 만드는 중입니다. 방송이 끝나고 몇 분 걸릴 수 있습니다.',
  failed: '다시보기를 만들지 못했습니다.',
  deleted: '보관 기간(30일)이 지나 영상이 삭제되었습니다.',
}
