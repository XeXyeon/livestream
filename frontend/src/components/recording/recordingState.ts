import type { BadgeVariant } from '@astryxdesign/core/Badge'
import type { BroadcastStatus, RecordingDetail, RecordingStatus } from '../../api/types'

export const BROADCAST_STATUS_BADGE: Record<BroadcastStatus, { label: string; variant: BadgeVariant }> = {
  LIVE: { label: 'LIVE', variant: 'error' },
  DISCONNECTED: { label: '재연결 대기', variant: 'warning' },
  ENDED: { label: '종료', variant: 'neutral' },
}

/** 다시보기 상태를 한 줄로(마이페이지 목록 · 다시보기 화면 공용) */
export const RECORDING_STATUS_TEXT: Record<RecordingStatus, string> = {
  RECORDING: '녹화 중',
  PROCESSING: '다시보기를 만드는 중',
  READY: '다시보기 있음',
  FAILED: '다시보기를 만들지 못했습니다',
  FILE_DELETED: '보관 기간(30일)이 지나 영상이 삭제되었습니다',
}

/**
 * 다시보기 화면에서 무엇을 보여 줄지(§4.10 서빙 규칙).
 * - public: videoUrl을 그대로 재생
 * - private: 주인이 video-token을 받아 재생
 * - unavailable: READY인데 재생 주소가 없음(공개 폴더 이동이 되돌려진 드문 경우)
 */
export type RecordingPlayback = 'public' | 'private' | 'unavailable' | 'processing' | 'failed' | 'deleted'

export function recordingPlayback(recording: Pick<RecordingDetail, 'status' | 'visibility' | 'videoUrl' | 'isOwner'>): RecordingPlayback {
  switch (recording.status) {
    case 'RECORDING':
    case 'PROCESSING':
      return 'processing'
    case 'FAILED':
      return 'failed'
    case 'FILE_DELETED':
      return 'deleted'
    case 'READY':
      if (recording.videoUrl) return 'public'
      if (recording.visibility === 'PRIVATE' && recording.isOwner) return 'private'
      return 'unavailable'
  }
}

/** 공개 설정을 바꿀 수 있는 다시보기(파일이 남아 있는 것). 서버는 모든 상태를 받지만 의미 없는 곳에는 스위치를 숨긴다 */
export const canChangeVisibility = (status: RecordingStatus) => status !== 'FILE_DELETED' && status !== 'FAILED'
