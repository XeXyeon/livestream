import { Badge } from '@astryxdesign/core/Badge'
import type { ChatEntry } from './chatState'

// 채팅 한 줄. 방송인은 배지. 후원은 FE-6에서 강조 · 배너를 붙이고, 지금은 금액이 보이는 한 줄로만 보인다.
export default function ChatMessage({ entry }: { entry: ChatEntry }) {
  if (entry.type === 'donation') {
    return (
      <li className="rounded-md bg-warning-muted px-2 py-1 text-on-warning-muted">
        <span className="font-semibold">{entry.donor.nickname}</span>님이 {entry.amount.toLocaleString('ko-KR')}원 후원
        {entry.message && <span>: {entry.message}</span>}
      </li>
    )
  }
  const owner = entry.member.isChannelOwner
  return (
    <li className="break-words px-2 py-0.5 text-primary">
      {owner && <span className="mr-1 inline-block align-middle"><Badge variant="info" label="방송인" /></span>}
      <span className={`mr-1.5 font-semibold ${owner ? 'text-accent' : 'text-secondary'}`}>{entry.member.nickname}</span>
      <span className="whitespace-pre-wrap">{entry.content}</span>
    </li>
  )
}
