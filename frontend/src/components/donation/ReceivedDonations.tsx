import { useQuery } from '@tanstack/react-query'
import { Banner } from '@astryxdesign/core/Banner'
import { Heading } from '@astryxdesign/core/Heading'
import { getReceivedDonations } from '../../api/donations'
import Spinner from '../common/Spinner'

const won = (amount: number) => `${amount.toLocaleString('ko-KR')}원`
const date = (iso: string) => new Date(iso).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })

// §6.7 S8 받은 후원: 내 채널이 받은 후원(승인만) 합계와 방송별 합계(GET /members/me/donations/received)
export default function ReceivedDonations() {
  const received = useQuery({ queryKey: ['me', 'donations', 'received'], queryFn: getReceivedDonations })

  return (
    <section aria-labelledby="received-donations" className="rounded-lg border border-border bg-surface p-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Heading level={2} id="received-donations">받은 후원</Heading>
        {received.data && <p className="text-sm text-secondary">총 <span className="text-lg font-bold text-primary">{won(received.data.totalAmount)}</span></p>}
      </div>
      {received.isPending && <Spinner label="받은 후원을 불러오는 중" />}
      {received.isError && <div className="mt-3"><Banner role="alert" status="error" title="받은 후원을 불러오지 못했습니다." /></div>}
      {received.data && received.data.byBroadcast.length === 0 && (
        <p className="mt-4 text-sm text-secondary">아직 받은 후원이 없습니다.</p>
      )}
      {received.data && received.data.byBroadcast.length > 0 && (
        <ul className="mt-4 divide-y divide-border">
          {received.data.byBroadcast.map((row) => (
            <li key={row.broadcastId} className="flex flex-wrap items-center justify-between gap-2 py-3">
              <div className="min-w-0">
                <p className="truncate font-medium text-primary">{row.title}</p>
                <p className="text-xs text-secondary">{date(row.startedAt)} · {row.count.toLocaleString('ko-KR')}건</p>
              </div>
              <p className="font-semibold text-primary">{won(row.totalAmount)}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
