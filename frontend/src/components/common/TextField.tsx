import type { InputHTMLAttributes, ReactNode } from 'react'

// 라벨 + 네이티브 input. ASTRYX TextInput은 required · minLength · maxLength를 타입으로 받지 않아
// 브라우저 기본 검증을 그대로 쓰는 폼은 이 컴포넌트로 ASTRYX 토큰 모양만 맞춘다.
type Props = InputHTMLAttributes<HTMLInputElement> & { label: ReactNode; hint?: ReactNode }

export const inputClassName = 'w-full rounded-md border border-border bg-body px-3 py-2.5 text-primary placeholder:text-secondary focus:border-border-strong focus:outline-2 focus:outline-offset-1 focus:outline-accent disabled:opacity-50'

export default function TextField({ label, hint, className, ...input }: Props) {
  return (
    <label className="block text-sm font-medium text-primary">
      {label}
      <input {...input} className={`mt-2 ${inputClassName} ${className ?? ''}`} />
      {hint && <span className="mt-1 block text-xs font-normal text-secondary">{hint}</span>}
    </label>
  )
}
