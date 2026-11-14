import { useState } from 'react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Card } from '@astryxdesign/core/Card'
import { Heading } from '@astryxdesign/core/Heading'
import type { FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { signup } from '../api/auth'
import { ApiError } from '../api/client'
import TextField from '../components/common/TextField'
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
    <Card padding={8} className="mx-auto max-w-md">
      <Heading level={1}>회원가입</Heading>
      <p className="mt-2 text-sm text-secondary">가입하면 내 방송 채널이 함께 만들어집니다.</p>
      <form onSubmit={(event) => void handleSubmit(event)} className="mt-7 space-y-5">
        <TextField label="이메일" type="email" required autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} />
        <TextField label="닉네임" type="text" required minLength={2} maxLength={20} autoComplete="nickname" value={nickname} onChange={(event) => setNickname(event.target.value)} />
        <TextField label="비밀번호" type="password" required minLength={8} maxLength={64} autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} />
        {error && <Banner role="alert" status="error" title={error} />}
        <Button type="submit" variant="primary" size="lg" width="100%" isLoading={submitting} label={submitting ? '가입 중…' : '회원가입'} />
      </form>
      <p className="mt-6 text-center text-sm text-secondary">이미 계정이 있나요? <Link to={`/login?next=${encodeURIComponent(next)}`} className="font-medium text-accent hover:underline">로그인</Link></p>
    </Card>
  )
}
