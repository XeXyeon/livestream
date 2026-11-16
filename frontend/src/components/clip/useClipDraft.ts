import { queryOptions, useQuery } from '@tanstack/react-query'
import { getDraft } from '../../api/clips'

export const DRAFT_POLL_MS = 2_000

/**
 * 라이브 클립 초안 질의(§4.16 GET /clip-drafts/{id}). PENDING이면 2초마다 다시 부르고 READY · FAILED가 되면 멈춘다.
 * READY 응답은 부를 때마다 새 재생 토큰을 담아 videoUrl이 바뀌므로(→ 영상이 처음부터 다시 로드), 끝난 뒤에는
 * 창 포커스 등으로 다시 부르지 않는다.
 */
export const clipDraftQueryOptions = (draftId: number) => queryOptions({
  queryKey: ['clipDraft', draftId],
  queryFn: () => getDraft(draftId),
  refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? DRAFT_POLL_MS : false),
  staleTime: (query) => (query.state.data?.status === 'PENDING' ? 0 : Infinity),
  refetchOnWindowFocus: false,
  retry: false,
})

/** 초안 상태. id가 null이면 부르지 않는다 */
export function useClipDraft(draftId: number | null) {
  return useQuery({ ...clipDraftQueryOptions(draftId ?? 0), enabled: draftId !== null })
}
