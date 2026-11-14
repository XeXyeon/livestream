/** 팔로우 버튼이 무엇을 보일지 정하는 데 쓰는 값 */
export type FollowViewInput = {
  /** 자기 채널(isOwner 또는 member.channelId === channelId) */
  isMine: boolean
  /** 로그인 복구가 끝났는지(useAuth.ready) */
  ready: boolean
  loggedIn: boolean
  /** 내 팔로우 목록 상태. 목록이 있으면 그 안에 이 채널이 있는지 */
  list: { status: 'pending' | 'error' } | { status: 'success'; has: boolean; updatedAt: number }
  /** 이 회원 · 이 채널의 마지막 팔로우 · 해제 응답 */
  latest: { following: boolean; at: number } | null
}

export type FollowView =
  | { kind: 'count' } // 팔로워 수만(자기 채널 · 복구 전 · 목록 실패)
  | { kind: 'login' } // 로그인 링크 버튼
  | { kind: 'loading' } // 목록을 받는 중: 누를 수 없음
  | { kind: 'toggle'; following: boolean }

/**
 * 팔로우 여부는 내 팔로우 목록이 기준이다. 상세 응답의 isFollowing은 로그인 복구 전에 받았거나
 * 이전 계정 때 받은 값일 수 있어 쓰지 않는다. 마지막 응답은 목록을 다시 받기 전까지만 쓴다.
 */
export function followView({ isMine, ready, loggedIn, list, latest }: FollowViewInput): FollowView {
  if (isMine || !ready) return { kind: 'count' }
  if (!loggedIn) return { kind: 'login' }
  if (latest && (list.status !== 'success' || list.updatedAt < latest.at)) return { kind: 'toggle', following: latest.following }
  if (list.status === 'success') return { kind: 'toggle', following: list.has }
  if (list.status === 'pending') return { kind: 'loading' }
  return { kind: 'count' }
}
