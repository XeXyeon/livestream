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
export type BroadcastTopicMessage = ChatMessage | DonationMessage | StatusMessage | ViewersMessage | HistoryMessage

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
