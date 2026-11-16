import { queryOptions, useQuery } from '@tanstack/react-query'
import { getClip } from '../../api/clips'

export const CLIP_POLL_MS = 2_000

/** 클립 상세 질의(§4.16 GET /clips/{id}). PROCESSING이면 2초마다 다시 부르고, READY · FAILED가 되면 멈춘다 */
export const clipQueryOptions = (clipId: number) => queryOptions({
  queryKey: ['clip', clipId],
  queryFn: () => getClip(clipId),
  refetchInterval: (query) => (query.state.data?.status === 'PROCESSING' ? CLIP_POLL_MS : false),
})

/** 클립 상태. id가 null이거나 enabled가 거짓이면 부르지 않는다 */
export function useClipStatus(clipId: number | null, enabled = true) {
  return useQuery({ ...clipQueryOptions(clipId ?? 0), enabled: enabled && clipId !== null })
}
