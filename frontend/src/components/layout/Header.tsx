import { useState } from 'react'
import { Link, Outlet } from 'react-router-dom'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { LinkProvider } from '@astryxdesign/core/Link'
import { logout } from '../../api/auth'
import { ApiError } from '../../api/client'
import { useAuth } from '../../store/auth'

// 앱 공통 셸: 헤더 + 본문. ASTRYX의 href 링크(Button · Link)가 react-router Link로 이동하도록 LinkProvider를 여기서 둔다
// (BrowserRouter 안이어야 한다). 셸 밖인 /widget은 링크가 없어 필요 없다.
export default function Header() {
  const member = useAuth((state) => state.member)
  const ready = useAuth((state) => state.ready)
  const clear = useAuth((state) => state.clear)
  const [error, setError] = useState('')

  async function handleLogout() {
    setError('')
    try {
      await logout()
      clear()
      window.location.replace('/')
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '로그아웃하지 못했습니다. 다시 시도해 주세요.')
    }
  }

  return (
    <LinkProvider component={Link}>
      <div className="min-h-screen bg-body text-primary">
        <header className="border-b border-border bg-surface">
          <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3 sm:px-5">
            <Link to="/" className="shrink-0 text-lg font-bold text-primary sm:text-xl">그리드라이브</Link>
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
          {error && (
            <div className="mx-auto max-w-6xl px-4 pb-3 sm:px-5">
              <Banner role="alert" status="error" title={error} />
            </div>
          )}
        </header>
        <main className="mx-auto max-w-6xl px-4 py-8 sm:px-5 sm:py-10"><Outlet /></main>
      </div>
    </LinkProvider>
  )
}
