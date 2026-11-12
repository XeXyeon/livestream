import { Badge } from '@astryxdesign/core/Badge'
import type { ChatEntry } from './chatState'

// 채팅 한 줄. 방송인은 배지. 후원은 테두리 있는 칸에 금액을 크게 보여 강조한다(FE-6).
export default function ChatMessage({ entry }: { entry: ChatEntry }) {
  if (entry.type === 'donation') {
    return (
      <li className="my-1 rounded-md border border-border-warning bg-warning-muted px-3 py-2 text-on-warning-muted">
        <p className="flex flex-wrap items-baseline gap-x-1.5">
          <span className="font-semibold">{entry.donor.nickname}</span>
          <span className="text-base font-bold">{entry.amount.toLocaleString('ko-KR')}원</span>
          <span className="text-xs">후원</span>
        </p>
        {entry.message && <p className="mt-0.5 whitespace-pre-wrap break-words">{entry.message}</p>}
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
