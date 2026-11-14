import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../../store/auth'

export default function Protected() {
  const ready = useAuth((state) => state.ready)
  const member = useAuth((state) => state.member)
  const location = useLocation()

  if (!ready) return <p role="status" className="text-center text-secondary">로그인 상태를 확인하고 있습니다.</p>
  if (!member) {
    const next = `${location.pathname}${location.search}${location.hash}`
    return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />
  }
  return <Outlet />
}
