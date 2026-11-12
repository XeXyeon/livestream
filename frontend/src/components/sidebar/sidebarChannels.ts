import type { BroadcastSummary, Category, FollowedChannel, RecordingSummary } from '../../api/types'

export type SidebarChannel = {
  channelId: number
  name: string
  category: Category
  /** 라이브면 시청자 수, 아니면 null */
  viewerCount: number | null
  /** 추천 채널의 최근 방송 시각 */
  lastBroadcastAt: string | null
  href: string
}

/**
 * 사이드바 두 묶음을 만든다(팔로우 기능이 없어 기존 목록 API로 채운다).
 * - live: 진행 중 방송(시청자 수 내림차순으로 온 순서)을 채널별 하나씩
 * - recommended: 최근 공개 다시보기의 채널 중 라이브가 아닌 것, 중복 없이 recommendedLimit개
 */
export function buildSidebarChannels(
  lives: readonly BroadcastSummary[],
  recordings: readonly RecordingSummary[],
  recommendedLimit = 5,
): { live: SidebarChannel[]; recommended: SidebarChannel[] } {
  const liveIds = new Set<number>()
  const live: SidebarChannel[] = []
  for (const broadcast of lives) {
    if (liveIds.has(broadcast.channel.id)) continue
    liveIds.add(broadcast.channel.id)
    live.push({
      channelId: broadcast.channel.id,
      name: broadcast.channel.name,
      category: broadcast.category,
      viewerCount: broadcast.viewerCount,
      lastBroadcastAt: null,
      href: `/live/${broadcast.channel.id}`,
    })
  }
  const seen = new Set(liveIds)
  const recommended: SidebarChannel[] = []
  for (const recording of recordings) {
    if (recommended.length >= recommendedLimit) break
    if (seen.has(recording.channel.id)) continue
    seen.add(recording.channel.id)
    recommended.push({
      channelId: recording.channel.id,
      name: recording.channel.name,
      category: recording.category,
      viewerCount: null,
      lastBroadcastAt: recording.startedAt,
      href: `/channel/${recording.channel.id}`,
    })
  }
  return { live, recommended }
}

/** "팔로우" 칸: 서버 정렬(라이브 먼저) 그대로. 누르면 시청 화면(방송이 없으면 그 화면이 채널 소개를 보여 준다) */
export function followsToSidebar(follows: readonly FollowedChannel[]): SidebarChannel[] {
  return follows.map((follow) => ({
    channelId: follow.channelId,
    name: follow.name,
    category: follow.category,
    viewerCount: follow.isLive ? follow.viewerCount : null,
    lastBroadcastAt: null,
    href: `/live/${follow.channelId}`,
  }))
}

/** 사이드바가 없는 화면: 스튜디오(전체 화면 송출 도구) · 로그인 · 가입(폼에 집중) */
export function hasSidebar(pathname: string): boolean {
  if (pathname === '/login' || pathname === '/signup') return false
  return !(pathname === '/studio' || pathname.startsWith('/studio/'))
}

const STORAGE_KEY = 'gridlive.sidebar.collapsed'

/** 접힘 상태 읽기. 저장소를 못 쓰면(사생활 보호 모드 등) 펼친 상태 */
// 기본 저장소는 try 안에서 고른다: 쿠키 차단 · sandbox에서는 localStorage에 접근하는 순간 SecurityError가 난다.
export function readCollapsed(storage?: Pick<Storage, 'getItem'> | null): boolean {
  try {
    return (storage === undefined ? globalThis.localStorage : storage)?.getItem(STORAGE_KEY) === '1'
  } catch {
    return false
  }
}

export function writeCollapsed(collapsed: boolean, storage?: Pick<Storage, 'setItem'> | null): void {
  try {
    (storage === undefined ? globalThis.localStorage : storage)?.setItem(STORAGE_KEY, collapsed ? '1' : '0')
  } catch {
    // 기억하지 못해도 지금 화면은 동작한다
  }
}
