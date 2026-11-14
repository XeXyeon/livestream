import { useState } from 'react'
import type { FormEvent } from 'react'
import { Button } from '@astryxdesign/core/Button'
import { ApiError } from '../../api/client'
import type { PostWriteInput } from '../../api/types'
import TextField, { inputClassName } from '../common/TextField'
import { countChars } from '../comment/commentTree'

export const POST_TITLE_MAX = 100
export const POST_CONTENT_MAX = 5000

type Props = {
  initial?: PostWriteInput
  submitLabel: string
  onSubmit: (input: PostWriteInput) => Promise<unknown>
  onCancel: () => void
}

/** 게시글 작성 · 수정 폼(서버 규칙: 제목 1~100자 한 줄, 본문 1~5,000자) */
export default function PostEditor({ initial = { title: '', content: '' }, submitLabel, onSubmit, onCancel }: Props) {
  const [title, setTitle] = useState(initial.title)
  const [content, setContent] = useState(initial.content)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const titleLength = countChars(title.trim())
  const contentLength = countChars(content.trim())
  const titleTooLong = titleLength > POST_TITLE_MAX
  const contentTooLong = contentLength > POST_CONTENT_MAX
  const valid = titleLength > 0 && contentLength > 0 && !titleTooLong && !contentTooLong

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!valid) return
    setPending(true)
    setError('')
    try {
      await onSubmit({ title: title.trim(), content: content.trim() })
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '게시글을 저장하지 못했습니다.')
      setPending(false)
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4 rounded-lg border border-border bg-surface p-5">
      <TextField label="제목" required value={title} onChange={(event) => setTitle(event.target.value)} hint={titleTooLong ? `제목은 ${POST_TITLE_MAX}자까지 쓸 수 있습니다.` : undefined} />
      <label className="block text-sm font-medium text-primary">
        본문
        <textarea
          required
          rows={8}
          value={content}
          onChange={(event) => setContent(event.target.value)}
          className={`mt-2 resize-y ${inputClassName}`}
        />
        <span className={`mt-1 block text-right text-xs font-normal ${contentTooLong ? 'text-error' : 'text-secondary'}`}>{contentLength.toLocaleString('ko-KR')}/{POST_CONTENT_MAX.toLocaleString('ko-KR')}</span>
      </label>
      {error && <p role="alert" className="text-sm text-error">{error}</p>}
      <div className="flex justify-end gap-2">
        <Button label="취소" variant="ghost" onClick={onCancel} />
        <Button type="submit" label={submitLabel} variant="primary" isLoading={pending} isDisabled={!valid} />
      </div>
    </form>
  )
}
