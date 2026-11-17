import { X } from 'lucide-react'
import { Button } from '@astryxdesign/core/Button'
import { Dialog, DialogHeader } from '@astryxdesign/core/Dialog'
import { IconButton } from '@astryxdesign/core/IconButton'
import { createClipFromDraft, createRecordingClip } from '../../api/clips'
import type { ClipDraftDetail, RecordingDetail } from '../../api/types'
import Spinner from '../common/Spinner'
import RecordingVideo from '../recording/RecordingVideo'
import ClipEditor from './ClipEditor'
import { defaultLiveClipStart } from './clipState'
import { useClipDraft } from './useClipDraft'

type Common = {
  isOpen: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (clipId: number) => void
}

type RecordingProps = Common & {
  mode: 'recording'
  /** 다이얼로그 안 미리보기도 같은 재생 규칙(공개 videoUrl · 비공개 video-token)으로 튼다 */
  recording: RecordingDetail
  /** 열 때 시작 위치 기본값(지금 재생 위치, 초) */
  initialStart: number
}

type LiveProps = Common & {
  mode: 'live'
  /** POST /broadcasts/{id}/clip-drafts 로 만든 초안 */
  draftId: number
  /** 제목 기본값(방송 제목) */
  defaultTitle: string
}

/**
 * 클립 만들기 다이얼로그(§6.7). 화면은 두 모드 모두 ClipEditor 하나다
 *   (미리보기 영상 · 구간 막대 + "구간 재생" · 시작 · 끝 위치 · 5~60초 안내 · 제목 · 공개 여부).
 * 여기서는 다이얼로그 틀 · 제목 줄 · 모드별 미리보기 영상과 요청만 정한다.
 * - recording(S6 "클립 만들기"): 미리보기 = 이 다시보기(RecordingVideo, 시작 = 열 때의 재생 위치). 영상이 길어 막대는 확대 창(4분)만 보인다.
 * - live(S2 "클립", CLIP-3): 초안(최근 2분)을 받는 동안 "준비하는 중", READY면 초안 영상이 미리보기, FAILED면 안내.
 *   닫아도 초안은 그대로 두고(서버가 10분 뒤 지움) 다시 누르면 새 초안을 만든다. 막대 = 초안 전체.
 * 만들면 닫고 상태 알림(ClipProgress)으로 넘긴다.
 */
export default function ClipDialog(props: RecordingProps | LiveProps) {
  const { isOpen, onOpenChange, onCreated } = props
  const onClose = () => onOpenChange(false)
  return (
    <Dialog isOpen={isOpen} onOpenChange={onOpenChange} purpose="form" width={640} maxHeight="90dvh">
      {/* key: 열 때마다 새로 채운다(다시보기는 지금 재생 위치, 라이브는 새 초안) */}
      {isOpen && props.mode === 'recording' && (
        <RecordingClipForm key={props.initialStart} recording={props.recording} initialStart={props.initialStart} onClose={onClose} onCreated={onCreated} />
      )}
      {isOpen && props.mode === 'live' && (
        <LiveDraftClip key={props.draftId} draftId={props.draftId} defaultTitle={props.defaultTitle} onClose={onClose} onCreated={onCreated} />
      )}
    </Dialog>
  )
}

type Done = { onClose: () => void; onCreated: (clipId: number) => void }

/** 제목 줄 오른쪽에 닫기(X). DialogHeader 기본 닫기 버튼은 이름이 영어(Close)라 직접 둔다 */
function ClipDialogHeader({ onClose }: { onClose: () => void }) {
  return (
    <DialogHeader
      title="클립 만들기"
      endContent={<IconButton label="닫기" icon={<X aria-hidden className="h-5 w-5" />} variant="ghost" size="sm" onClick={onClose} />}
    />
  )
}

function LiveDraftClip({ draftId, defaultTitle, onClose, onCreated }: { draftId: number; defaultTitle: string } & Done) {
  const { data, isError } = useClipDraft(draftId)
  return <LiveDraftStage draft={data ?? null} isError={isError} defaultTitle={defaultTitle} onClose={onClose} onCreated={onCreated} />
}

/**
 * 라이브 모드 본문: 초안 상태별 화면. 상태를 props로 받아 그리므로 테스트에서 그대로 렌더한다.
 * 초안 영상은 토큰이 주소에 있어 TokenVideo(토큰 재시도)가 필요 없다.
 */
export function LiveDraftStage({ draft, isError, defaultTitle, onClose, onCreated }: {
  draft: ClipDraftDetail | null
  isError: boolean
  defaultTitle: string
} & Done) {
  if (isError || draft?.status === 'FAILED' || (draft?.status === 'READY' && !draft.videoUrl)) {
    return (
      <>
        <ClipDialogHeader onClose={onClose} />
        <div className="space-y-5 px-2 pb-2">
          <p role="alert" className="text-sm text-error">클립을 만들 수 없습니다. 잠시 뒤 다시 시도해 주세요.</p>
          <div className="flex justify-end"><Button label="닫기" variant="secondary" onClick={onClose} /></div>
        </div>
      </>
    )
  }
  if (draft === null || draft.status === 'PENDING') {
    return (
      <>
        <ClipDialogHeader onClose={onClose} />
        <div className="space-y-5 px-2 pb-2">
          <div className="flex aspect-video w-full items-center justify-center rounded-md bg-surface">
            <Spinner label="최근 2분을 준비하는 중…" />
          </div>
          <div className="flex justify-end"><Button label="취소" variant="ghost" onClick={onClose} /></div>
        </div>
      </>
    )
  }
  return <LiveDraftForm draft={draft} videoUrl={draft.videoUrl ?? ''} defaultTitle={defaultTitle} onClose={onClose} onCreated={onCreated} />
}

/** 다시보기 모드: 미리보기는 이 다시보기, 막대는 확대 창 */
export function RecordingClipForm({ recording, initialStart, onClose, onCreated }: {
  recording: RecordingDetail
  initialStart: number
} & Done) {
  return (
    <>
      <ClipDialogHeader onClose={onClose} />
      <ClipEditor
        totalSeconds={recording.durationSeconds}
        initialStart={initialStart}
        zoom
        defaultTitle={recording.title}
        renderVideo={(ref, events) => <RecordingVideo recording={recording} startAt={initialStart} videoRef={ref} mediaEvents={events} />}
        submit={(body) => createRecordingClip(recording.id, body)}
        onClose={onClose}
        onCreated={onCreated}
      />
    </>
  )
}

/** 라이브 모드(READY): 미리보기는 초안 영상, 막대는 초안 전체 */
function LiveDraftForm({ draft, videoUrl, defaultTitle, onClose, onCreated }: {
  draft: ClipDraftDetail
  videoUrl: string
  defaultTitle: string
} & Done) {
  const initialStart = defaultLiveClipStart(draft.windowSeconds)
  return (
    <>
      <ClipDialogHeader onClose={onClose} />
      <ClipEditor
        totalSeconds={draft.windowSeconds}
        initialStart={initialStart}
        zoom={false}
        defaultTitle={defaultTitle}
        renderVideo={(ref, events) => (
          <video
            ref={ref}
            src={videoUrl}
            controls
            playsInline
            preload="metadata"
            // 다시보기 모드처럼 처음 화면을 클립 시작 위치로
            onLoadedMetadata={(event) => { event.currentTarget.currentTime = initialStart }}
            {...events}
            className="aspect-video w-full rounded-md bg-black"
          />
        )}
        submit={(body) => createClipFromDraft(draft.id, body)}
        onClose={onClose}
        onCreated={onCreated}
      />
    </>
  )
}
