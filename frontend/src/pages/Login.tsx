import { Card } from '@astryxdesign/core/Card'
import { Heading } from '@astryxdesign/core/Heading'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { LoginForm, SignedUpNotice } from '../components/auth/AuthForms'
import { safeNext } from './authNavigation'

// 직접 주소(/login)와 보호 경로 리다이렉트용 페이지. 화면 안의 로그인 버튼은 모달(AuthDialog)을 쓴다.
export default function Login() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const location = useLocation()
  const next = safeNext(params.get('next'))
  const signedUp = location.state as { signedUp?: boolean; email?: string } | null

  return (
    <Card padding={8} className="mx-auto max-w-md">
      <Heading level={1}>로그인</Heading>
      <LoginForm
        initialEmail={signedUp?.email ?? ''}
        notice={signedUp?.signedUp ? <SignedUpNotice /> : undefined}
        onLoggedIn={() => navigate(next, { replace: true })}
        footer={<>계정이 없나요? <Link to={`/signup?next=${encodeURIComponent(next)}`} className="font-medium text-accent hover:underline">회원가입</Link></>}
      />
    </Card>
  )
}
