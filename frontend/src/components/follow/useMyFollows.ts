import { useQuery } from '@tanstack/react-query'
import { listMyFollows } from '../../api/follows'
import { useAuth } from '../../store/auth'

/** 접두 키: 팔로우 · 해제 뒤 이것으로 무효화한다 */
export const MY_FOLLOWS_KEY = ['me', 'follows'] as const

/**
 * 내 팔로우 목록(사이드바 "팔로우" 칸 · 팔로우 버튼이 함께 쓴다). 로그인 상태에서만 부르고 30초마다 다시 부른다.
 * 키에 회원 id를 넣어 다른 계정으로 바뀌면 이전 목록을 쓰지 않는다.
 */
export function useMyFollows() {
  const memberId = useAuth((state) => state.member?.id ?? null)
  const ready = useAuth((state) => state.ready)
  const query = useQuery({
    queryKey: [...MY_FOLLOWS_KEY, memberId],
    queryFn: listMyFollows,
    enabled: ready && memberId !== null,
    refetchInterval: 30_000,
  })
  return { ...query, ready, loggedIn: memberId !== null }
}
