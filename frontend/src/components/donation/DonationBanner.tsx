import { HandCoins } from 'lucide-react'
import type { DonationMessage } from '../../api/types'

// §6.7 S2 DonationBanner(플레이어 위 5초) · S3 후원 알림. 표시 순서 · 시간은 useDonationBanner가 정한다.
// overlay: 영상 위에 띄울 때(테마 밖 고정 색, UI-1). 아니면 스튜디오 칸 안에 놓는 토큰 색.
// 낭독은 채팅 목록(aria-live)의 후원 줄이 맡는다. 배너까지 읽으면 같은 후원을 두 번 읽으므로 live region을 두지 않는다.
export default function DonationBanner({ donation, overlay = false }: { donation: DonationMessage | null; overlay?: boolean }) {
  if (!donation) return null
  const tone = overlay
    ? 'bg-amber-400/95 text-black shadow-lg'
    : 'border border-border-warning bg-warning-muted text-on-warning-muted'
  return (
    <div className={`flex items-start gap-3 rounded-lg px-4 py-3 ${tone}`}>
      <HandCoins aria-hidden className="mt-0.5 h-5 w-5 shrink-0" />
      <div className="min-w-0">
        <p className="font-semibold">
          {donation.donor.nickname}님이 {donation.amount.toLocaleString('ko-KR')}원 후원했습니다
        </p>
        {donation.message && <p className="mt-0.5 break-words text-sm">{donation.message}</p>}
      </div>
    </div>
  )
}
