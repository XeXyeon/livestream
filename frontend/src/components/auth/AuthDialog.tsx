import { Dialog, DialogHeader } from '@astryxdesign/core/Dialog'
import { IconButton } from '@astryxdesign/core/IconButton'
import { X } from 'lucide-react'
import { useAuthDialog } from '../../store/authDialog'
import { LoginForm, SignedUpNotice, SignupForm, TextAction } from './AuthForms'

/**
 * 로그인 · 가입 모달(헤더 셸에 하나). 로그인 → "회원가입"으로 같은 모달 안에서 가입 단계로 넘어가고,
 * 가입을 마치면 이메일을 채운 로그인 단계로 돌아온다. 로그인하면 닫고 지금 화면에 머문다.
 */
export default function AuthDialog() {
  const mode = useAuthDialog((state) => state.mode)
  const signedUpEmail = useAuthDialog((state) => state.signedUpEmail)
  const switchTo = useAuthDialog((state) => state.switchTo)
  const close = useAuthDialog((state) => state.close)
  const onOpenChange = (open: boolean) => { if (!open) close() }

  return (
    <Dialog isOpen={mode !== null} onOpenChange={onOpenChange} purpose="form" width={400} maxHeight="90dvh">
      {/* 닫기는 제목 줄과 나눠 맨 위 오른쪽 줄에 둔다. DialogHeader 기본 닫기 버튼은 이름이 영어(Close)라 직접 둔다 */}
      <div className="-mr-2 -mt-2 flex justify-end">
        <IconButton label="닫기" icon={<X aria-hidden className="h-5 w-5" />} variant="ghost" size="sm" onClick={close} />
      </div>
      {/* Dialog 안쪽은 max-height + overflow:hidden인 세로 flex다. 화면이 낮으면(휴대폰 · 가로 모드) 가입 폼이 잘리므로
          닫기 줄 아래 전체(제목 · 폼)를 세로 스크롤 영역으로 둔다 */}
      <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden overscroll-contain">
        <DialogHeader
          title={(
            <span className="flex flex-col items-center gap-3 text-center">
              <img src="/favicon.svg" alt="" aria-hidden className="h-11 w-11" />
              {mode === 'signup' ? '회원가입' : '그리드라이브 로그인'}
            </span>
          )}
        />
        <div className="px-2 pb-2">
        {mode === 'signup' ? (
          <SignupForm
            onSignedUp={(email) => switchTo('login', email)}
            footer={<>이미 계정이 있나요? <TextAction label="로그인" onClick={() => switchTo('login')} /></>}
          />
        ) : mode === 'login' ? (
          // key: 가입을 마치고 돌아오면 폼을 새로 만들어 이메일을 채운다
          <LoginForm
            key={signedUpEmail ?? ''}
            initialEmail={signedUpEmail ?? ''}
            notice={signedUpEmail ? <SignedUpNotice /> : undefined}
            onLoggedIn={close}
            footer={<>계정이 없나요? <TextAction label="회원가입" onClick={() => switchTo('signup')} /></>}
          />
        ) : null}
        </div>
      </div>
    </Dialog>
  )
}
