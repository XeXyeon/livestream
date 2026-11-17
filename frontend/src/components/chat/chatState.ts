import type { BroadcastTopicMessage, ChatMessage, DonationMessage } from '../../api/types'

export type ChatEntry = ChatMessage | DonationMessage

/** 화면에 남겨 둘 최대 줄 수(서버 버퍼는 50, 그보다 길게 보여 준다) */
export const CHAT_LIMIT = 200
export const CHAT_MAX_LENGTH = 200

/** 채팅 연결이 `connected`가 아닐 때 목록 위에 보이는 문구(ChatPanel · ChatOverlay 공유) */
export const CONNECTION_TEXT = { connecting: '채팅에 연결하는 중…', reconnecting: '채팅 연결이 끊겨 다시 연결하는 중…' } as const

export function entryKey(entry: ChatEntry): string {
  return entry.type === 'chat' ? `c:${entry.id}` : `d:${entry.id}`
}

function isEntry(message: unknown): message is ChatEntry {
  const type = (message as { type?: unknown } | null)?.type
  return type === 'chat' || type === 'donation'
}

/**
 * 토픽 메시지를 채팅 목록에 반영한다. status · viewers는 목록을 바꾸지 않는다.
 * history는 구독할 때마다(재연결 포함) 오므로 이미 있는 줄은 건너뛰고 없는 것만 붙인다(처음이면 그대로 목록이 된다).
 * history보다 먼저 도착한 실시간 메시지가 있을 수 있어, history를 합친 뒤에는 시각 순으로 다시 놓는다(같은 시각은 원래 순서).
 */
export function applyChatMessage(entries: ChatEntry[], message: BroadcastTopicMessage): ChatEntry[] {
  let incoming: ChatEntry[]
  if (message.type === 'history') incoming = (message.messages ?? []).filter(isEntry)
  else if (isEntry(message)) incoming = [message]
  else return entries

  const seen = new Set(entries.map(entryKey))
  const added = incoming.filter((entry) => {
    const key = entryKey(entry)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
  if (added.length === 0) return entries
  let next = [...entries, ...added]
  if (message.type === 'history') next = sortByTime(next)
  return next.length > CHAT_LIMIT ? next.slice(next.length - CHAT_LIMIT) : next
}

function entryTime(entry: ChatEntry): number {
  const time = Date.parse(entry.type === 'chat' ? entry.sentAt : entry.approvedAt)
  return Number.isNaN(time) ? 0 : time
}

function sortByTime(entries: ChatEntry[]): ChatEntry[] {
  return entries.map((entry, index) => ({ entry, index, time: entryTime(entry) }))
    .sort((a, b) => a.time - b.time || a.index - b.index)
    .map(({ entry }) => entry)
}

/** 보낼 내용 검사: 앞뒤 공백을 뺀 1~200자. 문제가 없으면 null */
export function chatInputError(content: string): string | null {
  const trimmed = content.trim()
  if (!trimmed) return '메시지를 입력해 주세요.'
  if ([...trimmed].length > CHAT_MAX_LENGTH) return `메시지는 ${CHAT_MAX_LENGTH}자까지 보낼 수 있습니다.`
  return null
}
