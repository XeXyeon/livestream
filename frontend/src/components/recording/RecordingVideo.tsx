import { useCallback } from 'react'
import type { RefObject } from 'react'
import { createVideoToken } from '../../api/recordings'
import type { RecordingDetail } from '../../api/types'
import { recordingPlayback } from './recordingState'
import type { RecordingPlayback } from './recordingState'
import TokenVideo from './TokenVideo'

/** S6 영상. 공개는 videoUrl, 비공개(주인)는 5분짜리 video-token 주소로 재생한다(TokenVideo) */
export default function RecordingVideo({ recording, startAt, videoRef }: {
  recording: RecordingDetail
  /** `?t=`(클립 → 원본 링크): 이 위치부터 */
  startAt?: number
  videoRef?: RefObject<HTMLVideoElement | null>
}) {
  const fetchToken = useCallback(() => createVideoToken(recording.id), [recording.id])
  return (
    <TokenVideo
      mediaKey={`recording-${recording.id}`}
      playback={recordingPlayback(recording)}
      videoUrl={recording.videoUrl}
      poster={recording.thumbnailUrl}
      fetchToken={fetchToken}
      overlayText={OVERLAY_TEXT}
      startAt={startAt}
      videoRef={videoRef}
    />
  )
}

const OVERLAY_TEXT: Record<RecordingPlayback, string> = {
  public: '영상을 불러오는 중…',
  private: '재생 주소를 받는 중…',
  unavailable: '지금은 영상을 재생할 수 없습니다.',
  processing: '다시보기를 만드는 중입니다. 방송이 끝나고 몇 분 걸릴 수 있습니다.',
  failed: '다시보기를 만들지 못했습니다.',
  deleted: '보관 기간(30일)이 지나 영상이 삭제되었습니다.',
}
