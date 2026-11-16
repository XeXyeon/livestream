export type Member = {
  id: number
  nickname: string
  channelId: number
}

export type AuthResponse = {
  accessToken: string
  member: Member
}

export type SignupResponse = { memberId: number }

export type ApiErrorResponse = { code: string; message: string }

// §4.4 목록 응답
export type Page<T> = {
  items: T[]
  page: number
  size: number
  totalElements: number
  hasNext: boolean
}

// §4.3 카테고리 enum (응답은 code + label)
export const CATEGORIES = [
  { code: 'GAME', label: '게임' },
  { code: 'TALK', label: '토크' },
  { code: 'MUSIC', label: '음악' },
  { code: 'STUDY', label: '공부' },
  { code: 'ETC', label: '기타' },
] as const
export type CategoryCode = typeof CATEGORIES[number]['code']
export type Category = { code: CategoryCode; label: string }

export type BroadcastStatus = 'LIVE' | 'DISCONNECTED' | 'ENDED'
export type BroadcastSource = 'BROWSER_PC' | 'BROWSER_MOBILE' | 'STREAM_KEY'
export type PlayerMode = 'whep' | 'grid'
export type ChannelRef = { id: number; name: string }

export type BroadcastSummary = {
  id: number
  title: string
  category: Category
  thumbnailUrl: string | null
  status: BroadcastStatus
  startedAt: string
  viewerCount: number
  channel: ChannelRef
}

export type RecordingRef = { id: number; status: RecordingStatus; visibility: Visibility }

export type BroadcastDetail = BroadcastSummary & {
  endedAt: string | null
  source: BroadcastSource
  recommendedMode: PlayerMode
  play: { whepUrl: string; hlsUrl: string }
  donationTotal: number
  recording: RecordingRef | null
  isOwner: boolean
  /** 이 방송 채널 기준(FOLLOW-1). 비로그인 · 자기 채널은 isFollowing false */
  followerCount: number
  isFollowing: boolean
}

export type RecordingStatus = 'RECORDING' | 'PROCESSING' | 'READY' | 'FAILED' | 'FILE_DELETED'
export type Visibility = 'PUBLIC' | 'PRIVATE'

export type RecordingSummary = {
  id: number
  broadcastId: number
  title: string
  category: Category
  thumbnailUrl: string | null
  durationSeconds: number | null
  startedAt: string
  channel: ChannelRef
  commentCount: number
}

/** GET /recordings/{id}. videoUrl은 READY · PUBLIC일 때만, 비공개는 주인이 video-token으로 받는다 */
export type RecordingDetail = RecordingSummary & {
  status: RecordingStatus
  visibility: Visibility
  videoUrl: string | null
  fileDeleted: boolean
  donationTotal: number
  isOwner: boolean
}
/** POST /recordings/{id}/video-token (5분) */
export type VideoToken = { token: string; videoUrl: string }

/** GET /members/me/broadcasts 항목 */
export type MyBroadcastItem = {
  id: number
  title: string
  category: Category
  thumbnailUrl: string | null
  status: BroadcastStatus
  startedAt: string
  endedAt: string | null
  recording: RecordingRef | null
}

export type Recommendations = { lives: BroadcastSummary[]; recordings: RecordingSummary[] }

export type ChannelResponse = {
  id: number
  name: string
  description: string | null
  category: Category
  liveBroadcastId: number | null
  recordingCount: number
  postCount: number
  owner: { nickname: string }
  followerCount: number
  /** 로그인한 회원이 팔로우 중이면 true. 비로그인 · 자기 채널은 false */
  isFollowing: boolean
}

/** POST · DELETE /channels/{id}/follow (둘 다 멱등) */
export type FollowStatus = { channelId: number; following: boolean; followerCount: number }

/** GET /members/me/follows 항목. 정렬은 서버 고정(라이브 먼저 · 시청자 수 순, 그다음 최근 팔로우 순) */
export type FollowedChannel = {
  channelId: number
  name: string
  category: Category
  isLive: boolean
  liveBroadcastId: number | null
  viewerCount: number
  followedAt: string
}

export type MyChannelResponse = ChannelResponse & {
  title: string
  streamKey: string
  widgetKey: string
  autoPublishRecording: boolean
  currentBroadcast: { id: number; status: BroadcastStatus } | null
  publish: {
    whipUrl: string
    obsWhipUrl: string
    obsWhipBearer: string
    rtmpUrl: string
    rtmpStreamKey: string
  }
  widgetUrl: string
}

export type ChannelUpdateInput = Partial<{
  name: string
  description: string
  title: string
  category: CategoryCode
  autoPublishRecording: boolean
}>

export type BroadcastUpdateInput = Partial<{ title: string; category: CategoryCode }>

// §4.14 STOMP `/topic/broadcasts/{id}` 메시지(type으로 구분)
export type ChatMessage = {
  type: 'chat'
  id: string
  member: { id: number; nickname: string; isChannelOwner: boolean }
  content: string
  sentAt: string
}
export type DonationMessage = {
  type: 'donation'
  id: number
  donor: { nickname: string }
  amount: number
  message: string | null
  approvedAt: string
}
export type StatusMessage = { type: 'status'; status: BroadcastStatus; at: string }
export type ViewersMessage = { type: 'viewers'; count: number }
export type HistoryMessage = { type: 'history'; messages: (ChatMessage | DonationMessage)[] }
/** 진행 중 방송의 제목 · 카테고리가 실제로 바뀌었을 때 한 번(현재 값 전체). history에는 없다 */
export type InfoMessage = { type: 'info'; title: string; category: Category; at: string }
export type BroadcastTopicMessage = ChatMessage | DonationMessage | StatusMessage | ViewersMessage | HistoryMessage | InfoMessage

// §4.13 후원
export type DonationCreateInput = { amount: number; message?: string }
/** 결제위젯에 넘길 주문 정보(201) */
export type DonationCreated = {
  donationId: number
  orderId: string
  amount: number
  clientKey: string
  orderName: string
  customerName: string
}
/** 결제위젯 성공 리다이렉트로 받은 값 그대로 */
export type DonationConfirmInput = { paymentKey: string; orderId: string; amount: number }
/** 승인된 후원 */
export type Donation = {
  id: number
  broadcastId: number
  donor: { nickname: string }
  amount: number
  message: string | null
  approvedAt: string
}
export type DonationPage = Page<Donation> & { totalAmount: number; totalCount: number }
export type ReceivedDonationByBroadcast = {
  broadcastId: number
  title: string
  startedAt: string
  totalAmount: number
  count: number
}
export type ReceivedDonations = { totalAmount: number; byBroadcast: ReceivedDonationByBroadcast[] }

// §4.12 댓글 · 게시글
/** 평면 목록(createdAt 오름차순). 트리는 프론트가 parentId로 만든다 */
export type Comment = {
  id: number
  parentId: number | null
  /** 삭제된 댓글이면 null("삭제된 댓글입니다") */
  content: string | null
  deleted: boolean
  author: { id: number; nickname: string; isChannelOwner: boolean }
  createdAt: string
  updatedAt: string
}
export type CommentWriteInput = { content: string; parentId?: number }

export type PostSummary = {
  id: number
  title: string
  /** 본문 앞부분(최대 100자) */
  preview: string
  commentCount: number
  createdAt: string
  updatedAt: string
}
export type Post = {
  id: number
  channel: { id: number; name: string; ownerNickname: string }
  title: string
  content: string
  commentCount: number
  createdAt: string
  updatedAt: string
  isOwner: boolean
}
export type PostWriteInput = { title: string; content: string }

// ---------- 통합 검색 (§4.16 /search) ----------

export type SearchType = 'channels' | 'lives' | 'recordings'

/** 검색 결과의 채널 카드. isLive · viewerCount는 /members/me/follows와 같은 규칙 */
export type ChannelSummary = {
  id: number
  name: string
  description: string | null
  category: Category
  isLive: boolean
  liveBroadcastId: number | null
  viewerCount: number
  followerCount: number
}

/** 요약의 한 종류: 앞쪽 최대 5개 + 조건에 맞는 전체 수 */
export type SearchGroup<T> = { items: T[]; total: number }

/** GET /search?q= — q는 서버가 정규화한 값 */
export type SearchResponse = {
  q: string
  channels: SearchGroup<ChannelSummary>
  lives: SearchGroup<BroadcastSummary>
  recordings: SearchGroup<RecordingSummary>
}
