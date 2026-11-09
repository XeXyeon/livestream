import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { signup } from '../api/auth'
import { ApiError } from '../api/client'
import { safeNext } from './authNavigation'

export default function Signup() {
  const [email, setEmail] = useState('')
  const [nickname, setNickname] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const next = safeNext(params.get('next'))

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      await signup({ email, nickname, password })
      navigate(`/login?next=${encodeURIComponent(next)}`, { replace: true, state: { signedUp: true } })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '회원가입하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-md rounded-2xl border border-slate-800 bg-slate-900 p-8">
      <h1 className="text-2xl font-bold">회원가입</h1>
      <p className="mt-2 text-sm text-slate-400">가입하면 내 방송 채널이 함께 만들어집니다.</p>
      <form onSubmit={(event) => void handleSubmit(event)} className="mt-7 space-y-5">
        <label className="block text-sm font-medium">이메일
          <input type="email" required autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} className="mt-2 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2.5 text-white" />
        </label>
        <label className="block text-sm font-medium">닉네임
          <input type="text" required minLength={2} maxLength={20} autoComplete="nickname" value={nickname} onChange={(event) => setNickname(event.target.value)} className="mt-2 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2.5 text-white" />
        </label>
        <label className="block text-sm font-medium">비밀번호
          <input type="password" required minLength={8} maxLength={64} autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} className="mt-2 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2.5 text-white" />
        </label>
        {error && <p role="alert" className="text-sm text-rose-300">{error}</p>}
        <button type="submit" disabled={submitting} className="w-full rounded-lg bg-indigo-500 px-4 py-3 font-semibold text-white hover:bg-indigo-400 disabled:opacity-50">{submitting ? '가입 중…' : '회원가입'}</button>
      </form>
      <p className="mt-6 text-center text-sm text-slate-400">이미 계정이 있나요? <Link to={`/login?next=${encodeURIComponent(next)}`} className="font-medium text-indigo-300 hover:text-indigo-200">로그인</Link></p>
    </section>
  )
}
