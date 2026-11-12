import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { login } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../store/auth'
import { safeNext } from './authNavigation'

export default function Login() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const setAuth = useAuth((state) => state.setAuth)
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const location = useLocation()
  const next = safeNext(params.get('next'))

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      setAuth(await login({ email, password }))
      navigate(next, { replace: true })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '로그인하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-md rounded-2xl border border-slate-800 bg-slate-900 p-8">
      <h1 className="text-2xl font-bold">로그인</h1>
      {location.state?.signedUp && <p role="status" className="mt-3 text-sm text-emerald-300">가입이 완료되었습니다. 로그인해 주세요.</p>}
      <form onSubmit={(event) => void handleSubmit(event)} className="mt-7 space-y-5">
        <label className="block text-sm font-medium">이메일
          <input type="email" required autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} className="mt-2 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2.5 text-white" />
        </label>
        <label className="block text-sm font-medium">비밀번호
          <input type="password" required autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} className="mt-2 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2.5 text-white" />
        </label>
        {error && <p role="alert" className="text-sm text-rose-300">{error}</p>}
        <button type="submit" disabled={submitting} className="w-full rounded-lg bg-indigo-500 px-4 py-3 font-semibold text-white hover:bg-indigo-400 disabled:opacity-50">{submitting ? '로그인 중…' : '로그인'}</button>
      </form>
      <p className="mt-6 text-center text-sm text-slate-400">계정이 없나요? <Link to={`/signup?next=${encodeURIComponent(next)}`} className="font-medium text-indigo-300 hover:text-indigo-200">회원가입</Link></p>
    </section>
  )
}
