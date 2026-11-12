import { useId, useState } from 'react'
import type { FormEvent } from 'react'
import { Button } from '@astryxdesign/core/Button'
import { ApiError } from '../../api/client'
import { inputClassName } from '../common/TextField'
import { COMMENT_MAX, countChars, validateComment } from './commentTree'

type Props = {
  label: string
  submitLabel: string
  initial?: string
  autoFocus?: boolean
  onSubmit: (content: string) => Promise<unknown>
  onCancel?: () => void
}

/** 댓글 작성 · 답글 · 수정 공용 입력칸. 성공하면 비우고(작성) 또는 onCancel로 닫는다(호출한 쪽이 결정) */
export default function CommentForm({ label, submitLabel, initial = '', autoFocus, onSubmit, onCancel }: Props) {
  const id = useId()
  const [content, setContent] = useState(initial)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const length = countChars(content.trim())
  const tooLong = length > COMMENT_MAX

  async function submit(event: FormEvent) {
    event.preventDefault()
    const invalid = validateComment(content)
    if (invalid) { setError(invalid); return }
    setPending(true)
    setError('')
    try {
      await onSubmit(content.trim())
      setContent('')
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '댓글을 저장하지 못했습니다.')
    } finally {
      setPending(false)
    }
  }

  return (
    <form onSubmit={submit} className="space-y-2">
      <label htmlFor={id} className="sr-only">{label}</label>
      <textarea
        id={id}
        rows={3}
        autoFocus={autoFocus}
        placeholder={label}
        value={content}
        onChange={(event) => setContent(event.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        className={`resize-y ${inputClassName}`}
      />
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className={`text-xs ${tooLong ? 'text-error' : 'text-secondary'}`}>{length}/{COMMENT_MAX}</span>
        <div className="flex gap-2">
          {onCancel && <Button label="취소" variant="ghost" size="sm" onClick={onCancel} />}
          <Button type="submit" label={submitLabel} variant="primary" size="sm" isLoading={pending} isDisabled={!content.trim() || tooLong} />
        </div>
      </div>
      {error && <p id={`${id}-error`} role="alert" className="text-sm text-error">{error}</p>}
    </form>
  )
}
