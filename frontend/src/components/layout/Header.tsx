import { useState } from 'react'
import { Link, Outlet, useNavigate } from 'react-router-dom'
import { logout } from '../../api/auth'
import { ApiError } from '../../api/client'
import { useAuth } from '../../store/auth'

export default function Header() {
  const member = useAuth((state) => state.member)
  const ready = useAuth((state) => state.ready)
  const clear = useAuth((state) => state.clear)
  const [error, setError] = useState('')
  const navigate = useNavigate()

  async function handleLogout() {
    setError('')
    try {
      await logout()
      clear()
      navigate('/')
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '로그아웃하지 못했습니다. 다시 시도해 주세요.')
    }
  }

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <header className="border-b border-slate-800">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-5 py-4">
          <Link to="/" className="text-xl font-bold text-white">그리드라이브</Link>
          <nav aria-label="주 메뉴" className="flex items-center gap-3 text-sm">
            {ready && (member ? (
              <>
                <Link to="/studio" className="rounded-lg bg-indigo-500 px-3 py-2 font-semibold text-white hover:bg-indigo-400">방송하기</Link>
                <Link to="/me" className="text-slate-200 hover:text-white">{member.nickname}님</Link>
                <button type="button" onClick={() => void handleLogout()} className="text-slate-300 hover:text-white">로그아웃</button>
              </>
            ) : (
              <>
                <Link to="/studio" className="rounded-lg bg-indigo-500 px-3 py-2 font-semibold text-white hover:bg-indigo-400">방송하기</Link>
                <Link to="/login" className="text-slate-200 hover:text-white">로그인</Link>
                <Link to="/signup" className="text-slate-200 hover:text-white">회원가입</Link>
              </>
            ))}
          </nav>
        </div>
        {error && <p role="alert" className="mx-auto max-w-6xl px-5 pb-3 text-sm text-rose-300">{error}</p>}
      </header>
      <main className="mx-auto max-w-6xl px-5 py-10"><Outlet /></main>
    </div>
  )
}
