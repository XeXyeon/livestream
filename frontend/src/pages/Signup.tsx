import { Card } from '@astryxdesign/core/Card'
import { Heading } from '@astryxdesign/core/Heading'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { SignupForm } from '../components/auth/AuthForms'
import { safeNext } from './authNavigation'

// 직접 주소(/signup)용 페이지. 화면 안에서는 로그인 모달의 "회원가입" 단계를 쓴다.
export default function Signup() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const next = safeNext(params.get('next'))

  return (
    <Card padding={8} className="mx-auto max-w-md">
      <Heading level={1}>회원가입</Heading>
      <SignupForm
        onSignedUp={(email) => navigate(`/login?next=${encodeURIComponent(next)}`, { replace: true, state: { signedUp: true, email } })}
        footer={<>이미 계정이 있나요? <Link to={`/login?next=${encodeURIComponent(next)}`} className="font-medium text-accent hover:underline">로그인</Link></>}
      />
    </Card>
  )
}
