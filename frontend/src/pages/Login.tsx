import { useState } from 'react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Card } from '@astryxdesign/core/Card'
import { Heading } from '@astryxdesign/core/Heading'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { login } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../store/auth'
import TextField from '../components/common/TextField'
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
    <Card padding={8} className="mx-auto max-w-md">
      <Heading level={1}>로그인</Heading>
      {location.state?.signedUp && <p role="status" className="mt-3 text-sm text-success">가입이 완료되었습니다. 로그인해 주세요.</p>}
      <form onSubmit={(event) => void handleSubmit(event)} className="mt-7 space-y-5">
        <TextField label="이메일" type="email" required autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} />
        <TextField label="비밀번호" type="password" required autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} />
        {error && <Banner role="alert" status="error" title={error} />}
        <Button type="submit" variant="primary" size="lg" width="100%" isLoading={submitting} label={submitting ? '로그인 중…' : '로그인'} />
      </form>
      <p className="mt-6 text-center text-sm text-secondary">계정이 없나요? <Link to={`/signup?next=${encodeURIComponent(next)}`} className="font-medium text-accent hover:underline">회원가입</Link></p>
    </Card>
  )
}
