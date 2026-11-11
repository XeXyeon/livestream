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
