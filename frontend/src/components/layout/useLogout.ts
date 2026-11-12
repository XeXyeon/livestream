import { useState } from 'react'
import { logout } from '../../api/auth'
import { ApiError } from '../../api/client'
import { useAuth } from '../../store/auth'

/** 로그아웃(헤더 · 마이페이지 공용). 성공하면 홈으로 새로 연다(캐시 · 구독을 모두 버린다) */
export function useLogout() {
  const clear = useAuth((state) => state.clear)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)

  async function run() {
    setError('')
    setPending(true)
    try {
      await logout()
      clear()
      window.location.replace('/')
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '로그아웃하지 못했습니다. 다시 시도해 주세요.')
      setPending(false)
    }
  }

  return { logout: run, error, pending }
}
