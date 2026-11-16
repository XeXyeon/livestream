import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'
import ReceivedDonations from '../components/donation/ReceivedDonations'
import { useLogout } from '../components/layout/useLogout'
import MyBroadcasts from '../components/me/MyBroadcasts'
import MyClips from '../components/clip/MyClips'
import { useAuth } from '../store/auth'

// S8 마이페이지(§6.3): 내 방송 기록(제목 수정 · 삭제 · 공개 설정) · 내 클립 · 받은 후원 · 로그아웃
export default function Me() {
  const member = useAuth((state) => state.member)
  const { logout, error, pending } = useLogout()

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Heading level={1}>마이페이지</Heading>
          {member && <p className="mt-1 text-sm text-secondary">{member.nickname}님</p>}
        </div>
        <div className="flex flex-wrap gap-2">
          {member && <Button href={`/channel/${member.channelId}`} label="내 채널" variant="secondary" />}
          <Button label="로그아웃" variant="ghost" isLoading={pending} onClick={() => void logout()} />
        </div>
      </div>
      {error && <Banner role="alert" status="error" title={error} />}
      <MyBroadcasts />
      <MyClips />
      <ReceivedDonations />
    </div>
  )
}
