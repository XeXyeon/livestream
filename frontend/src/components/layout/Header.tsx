import { Suspense, useEffect, useState } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Drawer, DrawerHeader } from '@astryxdesign/core/Drawer'
import { IconButton } from '@astryxdesign/core/IconButton'
import { LinkProvider } from '@astryxdesign/core/Link'
import { Menu, X } from 'lucide-react'
import { useAuth } from '../../store/auth'
import Spinner from '../common/Spinner'
import ChannelSidebar from '../sidebar/ChannelSidebar'
import ChannelSidebarList from '../sidebar/ChannelSidebarList'
import { hasSidebar } from '../sidebar/sidebarChannels'
import { useLogout } from './useLogout'

// 앱 공통 셸: 헤더 + (채널 사이드바) + 본문. ASTRYX의 href 링크(Button · Link)가 react-router Link로 이동하도록 LinkProvider를 여기서 둔다
// (BrowserRouter 안이어야 한다). 셸 밖인 /widget은 링크가 없어 필요 없다.
// 사이드바는 xl 이상에서 왼쪽에 고정, 그보다 좁으면 헤더의 메뉴 버튼으로 여는 서랍(UI-7).
export default function Header() {
  const member = useAuth((state) => state.member)
  const ready = useAuth((state) => state.ready)
  const { logout: handleLogout, error } = useLogout()
  const { pathname } = useLocation()
  const sidebar = hasSidebar(pathname)
  const [drawerOpen, setDrawerOpen] = useState(false)

  // 주소가 바뀌면(뒤로 가기 포함) 서랍을 닫는다
  const [drawerPath, setDrawerPath] = useState(pathname)
  if (drawerPath !== pathname) {
    setDrawerPath(pathname)
    setDrawerOpen(false)
  }

  // 서랍을 연 채 창을 xl 이상으로 넓히면 고정 사이드바가 보이므로 서랍을 닫는다
  useEffect(() => {
    const query = window.matchMedia('(min-width: 80rem)')
    const close = () => { if (query.matches) setDrawerOpen(false) }
    query.addEventListener('change', close)
    return () => query.removeEventListener('change', close)
  }, [])

  return (
    <LinkProvider component={Link}>
      <div className="min-h-screen bg-body text-primary">
        <header className="sticky top-0 z-30 border-b border-border bg-surface">
          <div className="flex h-14 items-center justify-between gap-3 px-4 sm:px-5">
            <div className="flex min-w-0 items-center gap-2">
              {sidebar && (
                <IconButton label="채널 목록 열기" icon={<Menu aria-hidden className="h-5 w-5" />} variant="ghost" size="sm" onClick={() => setDrawerOpen(true)} className="xl:hidden" />
              )}
              <Link to="/" className="shrink-0 text-lg font-bold text-primary sm:text-xl">그리드라이브</Link>
            </div>
            <nav aria-label="주 메뉴" className="flex items-center gap-1 sm:gap-2">
              {ready && (
                <>
                  <Button href="/studio" label="방송하기" variant="primary" size="sm" />
                  {member ? (
                    <>
                      <Button href="/me" label={`${member.nickname}님`} variant="ghost" size="sm" />
                      <Button label="로그아웃" variant="ghost" size="sm" onClick={() => void handleLogout()} />
                    </>
                  ) : (
                    <>
                      <Button href="/login" label="로그인" variant="ghost" size="sm" />
                      <Button href="/signup" label="회원가입" variant="ghost" size="sm" />
                    </>
                  )}
                </>
              )}
            </nav>
          </div>
        </header>
        <div className="flex">
          {sidebar && <ChannelSidebar />}
          <main className="mx-auto min-w-0 max-w-6xl flex-1 px-4 py-8 sm:px-5 sm:py-10">
            {error && <div className="mb-6"><Banner role="alert" status="error" title={error} /></div>}
            <Suspense fallback={<Spinner />}><Outlet /></Suspense>
          </main>
        </div>
        {sidebar && (
          <Drawer isOpen={drawerOpen} onOpenChange={setDrawerOpen} side="start" width={280} label="채널 목록" padding={2}>
            {/* DrawerHeader 기본 닫기 버튼은 이름이 영어(Close)라 직접 둔다 */}
            <DrawerHeader
              title="채널"
              endContent={<IconButton label="닫기" icon={<X aria-hidden className="h-5 w-5" />} variant="ghost" size="sm" onClick={() => setDrawerOpen(false)} />}
            />
            <ChannelSidebarList onNavigate={() => setDrawerOpen(false)} />
          </Drawer>
        )}
      </div>
    </LinkProvider>
  )
}
