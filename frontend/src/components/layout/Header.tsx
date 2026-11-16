import { Suspense, useEffect, useState, useSyncExternalStore } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Drawer, DrawerHeader } from '@astryxdesign/core/Drawer'
import { IconButton } from '@astryxdesign/core/IconButton'
import { LinkProvider } from '@astryxdesign/core/Link'
import { Menu, X } from 'lucide-react'
import { useAuth } from '../../store/auth'
import Spinner from '../common/Spinner'
import { useAuthDialog } from '../../store/authDialog'
import { usePlayerUi } from '../../store/playerUi'
import { useMediaQuery } from '../common/useMediaQuery'
import AuthDialog from '../auth/AuthDialog'
import ChannelSidebar from '../sidebar/ChannelSidebar'
import ChannelSidebarList from '../sidebar/ChannelSidebarList'
import { hasSidebar, readCollapsed, writeCollapsed } from '../sidebar/sidebarChannels'
import { useLogout } from './useLogout'

// 앱 공통 셸: 헤더 + (채널 사이드바) + 본문. ASTRYX의 href 링크(Button · Link)가 react-router Link로 이동하도록 LinkProvider를 여기서 둔다
// (BrowserRouter 안이어야 한다). 셸 밖인 /widget은 링크가 없어 필요 없다.
// 사이드바(UI-7): sm 이상은 펼침 · 접힘을 오가는 고정 사이드바(접히면 아바타 레일), sm 미만(휴대폰)은 서랍만.
// 헤더의 메뉴 버튼은 sm 이상에서 그 자리에서 접기 · 펼치기, 휴대폰에서는 서랍을 연다.
const WIDE_QUERY = '(min-width: 40rem)'

function subscribeWide(onChange: () => void) {
  const query = window.matchMedia(WIDE_QUERY)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}
const isWide = () => window.matchMedia(WIDE_QUERY).matches

export default function Header() {
  const member = useAuth((state) => state.member)
  const ready = useAuth((state) => state.ready)
  const { logout: handleLogout, error } = useLogout()
  const { pathname } = useLocation()
  // 시청 화면은 채팅을 오른쪽 끝까지 붙이므로 본문 폭 · 여백을 화면이 직접 정한다
  const fullBleed = /^\/live\/[^/]+$/.test(pathname)
  // 영화관 모드(시청 화면, lg 이상)에서는 채널 사이드바를 숨겨 영상 · 채팅만 남긴다
  const theater = usePlayerUi((state) => state.theater)
  const lgWide = useMediaQuery('(min-width: 64rem)')
  const sidebar = hasSidebar(pathname) && !(fullBleed && theater && lgWide)
  const [drawerOpen, setDrawerOpen] = useState(false)
  const openAuth = useAuthDialog((state) => state.open)
  const wide = useSyncExternalStore(subscribeWide, isWide)
  const [collapsed, setCollapsed] = useState(() => readCollapsed())
  const changeCollapsed = (next: boolean) => {
    setCollapsed(next)
    writeCollapsed(next)
  }

  // 주소가 바뀌면(뒤로 가기 포함) 서랍을 닫는다
  const [drawerPath, setDrawerPath] = useState(pathname)
  if (drawerPath !== pathname) {
    setDrawerPath(pathname)
    setDrawerOpen(false)
  }

  // 서랍을 연 채 창을 sm 이상으로 넓히면 고정 사이드바가 보이므로 서랍을 닫는다
  useEffect(() => {
    const query = window.matchMedia(WIDE_QUERY)
    const close = () => { if (query.matches) setDrawerOpen(false) }
    query.addEventListener('change', close)
    return () => query.removeEventListener('change', close)
  }, [])

  return (
    <LinkProvider component={Link}>
      <div className="min-h-screen bg-body text-primary">
        <header className="sticky top-0 z-30 bg-body">
          <div className="flex h-14 items-center justify-between gap-3 pl-2.5 pr-4 sm:pr-5">
            <div className="flex min-w-0 items-center gap-2">
              {sidebar && (
                <IconButton
                  label={wide ? (collapsed ? '사이드바 펼치기' : '사이드바 접기') : '채널 목록 열기'}
                  icon={<Menu aria-hidden className="h-5 w-5" />}
                  variant="ghost"
                  size="sm"
                  onClick={() => (wide ? changeCollapsed(!collapsed) : setDrawerOpen(true))}
                />
              )}
              <Link to="/" className="flex shrink-0 items-center gap-2 text-lg font-bold text-primary sm:text-xl">
                <img src="/favicon.svg" alt="" aria-hidden className="h-7 w-7" />
                그리드라이브
              </Link>
            </div>
            <nav aria-label="주 메뉴" className="flex items-center gap-1 sm:gap-2">
              {ready && (
                <>
                  {member ? (
                    <>
                      <Button href="/studio" label="방송하기" variant="primary" size="sm" />
                      <Button href="/me" label={`${member.nickname}님`} variant="ghost" size="sm" />
                      <Button label="로그아웃" variant="ghost" size="sm" onClick={() => void handleLogout()} />
                    </>
                  ) : (
                    <Button label="로그인" variant="primary" size="sm" onClick={() => openAuth('login')} />
                  )}
                </>
              )}
            </nav>
          </div>
        </header>
        <div className="flex">
          {sidebar && <ChannelSidebar wide={wide} collapsed={collapsed} onCollapsedChange={changeCollapsed} onExpand={() => setDrawerOpen(true)} />}
          <main className={fullBleed ? 'min-w-0 flex-1' : 'mx-auto min-w-0 max-w-6xl flex-1 px-4 py-8 sm:px-5 sm:py-10'}>
            {error && <div className="mb-6"><Banner role="alert" status="error" title={error} /></div>}
            <Suspense fallback={<Spinner />}><Outlet /></Suspense>
          </main>
        </div>
        <AuthDialog />
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
