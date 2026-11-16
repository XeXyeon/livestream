import { useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { login, signup } from '../../api/auth'
import { ApiError } from '../../api/client'
import { useAuth } from '../../store/auth'
import TextField from '../common/TextField'

// 로그인 · 가입 폼 본문. 모달(AuthDialog)과 페이지(/login · /signup)가 함께 쓴다. 성공한 뒤 어디로 갈지는 쓰는 쪽이 정한다.

type LoginFormProps = {
  initialEmail?: string
  /** 가입 직후 안내 */
  notice?: ReactNode
  onLoggedIn: () => void
  /** "회원가입" 누름(모달은 단계 전환, 페이지는 이동) */
  footer: ReactNode
}

export function LoginForm({ initialEmail = '', notice, onLoggedIn, footer }: LoginFormProps) {
  const [email, setEmail] = useState(initialEmail)
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const setAuth = useAuth((state) => state.setAuth)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      setAuth(await login({ email, password }))
      onLoggedIn()
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '로그인하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      {notice}
      <form onSubmit={(event) => void handleSubmit(event)} className="mt-6 space-y-4">
        <TextField label="이메일" type="email" required autoComplete="email" placeholder="name@example.com" value={email} onChange={(event) => setEmail(event.target.value)} />
        <TextField label="비밀번호" type="password" required autoComplete="current-password" placeholder="비밀번호 입력" value={password} onChange={(event) => setPassword(event.target.value)} />
        {error && <Banner role="alert" status="error" title={error} />}
        <Button type="submit" variant="primary" size="lg" width="100%" isLoading={submitting} label={submitting ? '로그인 중…' : '로그인'} />
      </form>
      <p className="mt-6 border-t border-border pb-1 pt-5 text-center text-sm text-secondary">{footer}</p>
    </>
  )
}

type SignupFormProps = {
  onSignedUp: (email: string) => void
  footer: ReactNode
}

export function SignupForm({ onSignedUp, footer }: SignupFormProps) {
  const [email, setEmail] = useState('')
  const [nickname, setNickname] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      await signup({ email, nickname, password })
      onSignedUp(email)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '회원가입하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <p className="mt-2 text-center text-sm text-secondary">가입하면 내 방송 채널이 함께 만들어집니다.</p>
      <form onSubmit={(event) => void handleSubmit(event)} className="mt-6 space-y-4">
        <TextField label="이메일" type="email" required autoComplete="email" placeholder="name@example.com" value={email} onChange={(event) => setEmail(event.target.value)} />
        <TextField label="닉네임" type="text" required minLength={2} maxLength={20} autoComplete="nickname" placeholder="2~20자" value={nickname} onChange={(event) => setNickname(event.target.value)} />
        <TextField label="비밀번호" type="password" required minLength={8} maxLength={64} autoComplete="new-password" placeholder="8자 이상" value={password} onChange={(event) => setPassword(event.target.value)} />
        {error && <Banner role="alert" status="error" title={error} />}
        <Button type="submit" variant="primary" size="lg" width="100%" isLoading={submitting} label={submitting ? '가입 중…' : '회원가입'} />
      </form>
      <p className="mt-6 border-t border-border pb-1 pt-5 text-center text-sm text-secondary">{footer}</p>
    </>
  )
}

export function SignedUpNotice() {
  return <p role="status" className="mt-3 text-center text-sm text-success">가입이 완료되었습니다. 로그인해 주세요.</p>
}

/** 폼 아래 "계정이 없나요? 회원가입" 같은 글자 버튼(모달 안 단계 전환용) */
export function TextAction({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="font-medium text-accent hover:underline">
      {label}
    </button>
  )
}
