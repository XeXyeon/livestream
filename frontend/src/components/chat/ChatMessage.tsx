import type { ChatEntry } from './chatState'

// 채팅 한 줄. 방송인은 배지. 후원은 FE-6에서 강조 · 배너를 붙이고, 지금은 금액이 보이는 한 줄로만 보인다.
export default function ChatMessage({ entry }: { entry: ChatEntry }) {
  if (entry.type === 'donation') {
    return (
      <li className="rounded-lg bg-amber-500/10 px-2 py-1 text-amber-200">
        <span className="font-semibold">{entry.donor.nickname}</span>님이 {entry.amount.toLocaleString('ko-KR')}원 후원
        {entry.message && <span className="text-amber-100">: {entry.message}</span>}
      </li>
    )
  }
  const owner = entry.member.isChannelOwner
  return (
    <li className="break-words px-2 py-0.5 text-slate-200">
      {owner && <span className="mr-1 rounded bg-indigo-600 px-1.5 py-0.5 text-[10px] font-bold text-white">방송인</span>}
      <span className={`mr-1.5 font-semibold ${owner ? 'text-indigo-300' : 'text-slate-400'}`}>{entry.member.nickname}</span>
      <span className="whitespace-pre-wrap">{entry.content}</span>
    </li>
  )
}
