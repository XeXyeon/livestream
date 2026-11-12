import { Heading } from '@astryxdesign/core/Heading'
import ReceivedDonations from '../components/donation/ReceivedDonations'

// S8 마이페이지. 지금은 받은 후원(FE-6)만 있다. 내 방송 기록(제목 수정 · 삭제 · 공개 설정) · 로그아웃은 FE-7이 붙인다.
export default function Me() {
  return (
    <div className="space-y-6">
      <Heading level={1}>마이페이지</Heading>
      <ReceivedDonations />
    </div>
  )
}
