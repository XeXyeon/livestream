import { useState } from 'react'
import type { FormEvent } from 'react'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { ApiError } from '../../api/client'
import { CATEGORIES } from '../../api/types'
import type { CategoryCode } from '../../api/types'
import { inputClassName } from '../common/TextField'
import type { BroadcastInfo } from './useBroadcastSession'

type Props = {
  initial: BroadcastInfo
  onChange: (info: BroadcastInfo) => void
  onSave: (info: BroadcastInfo) => Promise<void>
  compact?: boolean
  /** 저장 버튼 문구. null이면 버튼을 숨기고 Enter 제출도 무시한다(송출 전 PC 스튜디오: 시작할 때 입력값을 그대로 보낸다). */
  saveLabel?: string | null
}

// 방송 정보(제목 · 카테고리). 송출 전에는 시작할 때 함께 저장되고, 송출 중에도 "저장"으로 바꿀 수 있다.
export default function BroadcastInfoForm({ initial, onChange, onSave, compact = false, saveLabel = '정보 저장' }: Props) {
  const [info, setInfo] = useState(initial)
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  function update(next: BroadcastInfo) {
    setInfo(next)
    setMessage(null)
    onChange(next)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (saveLabel === null) return
    setSaving(true)
    try {
      await onSave({ ...info, title: info.title.trim() })
      setMessage({ ok: true, text: '저장했습니다.' })
    } catch (cause) {
      setMessage({ ok: false, text: cause instanceof ApiError ? cause.message : '저장하지 못했습니다.' })
    } finally {
      setSaving(false)
    }
  }

  const input = inputClassName.replace('py-2.5', 'py-2')
  return (
    <form onSubmit={(event) => void handleSubmit(event)} className={compact ? 'flex flex-wrap items-end gap-2' : 'space-y-3'}>
      <label className={`block text-sm font-medium text-primary ${compact ? 'min-w-0 flex-1' : ''}`}>방송 제목
        <input required maxLength={100} value={info.title} onChange={(event) => update({ ...info, title: event.target.value })} className={`mt-1 ${input}`} />
      </label>
      <label className="block text-sm font-medium text-primary">카테고리
        <select value={info.category} onChange={(event) => update({ ...info, category: event.target.value as CategoryCode })} className={`mt-1 ${input}`}>
          {CATEGORIES.map((category) => <option key={category.code} value={category.code}>{category.label}</option>)}
        </select>
      </label>
      {saveLabel !== null && <Button type="submit" label={saving ? '저장 중…' : saveLabel} variant="secondary" isLoading={saving} isDisabled={!info.title.trim()} />}
      {message && <div className="w-full"><Banner role={message.ok ? 'status' : 'alert'} status={message.ok ? 'success' : 'error'} title={message.text} /></div>}
    </form>
  )
}
