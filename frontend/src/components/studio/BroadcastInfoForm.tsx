import { useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError } from '../../api/client'
import { CATEGORIES } from '../../api/types'
import type { CategoryCode } from '../../api/types'
import type { BroadcastInfo } from './useBroadcastSession'

type Props = {
  initial: BroadcastInfo
  onChange: (info: BroadcastInfo) => void
  onSave: (info: BroadcastInfo) => Promise<void>
  compact?: boolean
}

// 방송 정보(제목 · 카테고리). 송출 전에는 시작할 때 함께 저장되고, 송출 중에도 "저장"으로 바꿀 수 있다.
export default function BroadcastInfoForm({ initial, onChange, onSave, compact = false }: Props) {
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

  const input = 'w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-white'
  return (
    <form onSubmit={(event) => void handleSubmit(event)} className={compact ? 'flex flex-wrap items-end gap-2' : 'space-y-3'}>
      <label className={`block text-sm font-medium ${compact ? 'min-w-0 flex-1' : ''}`}>방송 제목
        <input required maxLength={100} value={info.title} onChange={(event) => update({ ...info, title: event.target.value })} className={`mt-1 ${input}`} />
      </label>
      <label className="block text-sm font-medium">카테고리
        <select value={info.category} onChange={(event) => update({ ...info, category: event.target.value as CategoryCode })} className={`mt-1 ${input}`}>
          {CATEGORIES.map((category) => <option key={category.code} value={category.code}>{category.label}</option>)}
        </select>
      </label>
      <button type="submit" disabled={saving || !info.title.trim()} className="rounded-lg border border-slate-600 px-3 py-2 text-sm text-slate-200 hover:border-slate-400 disabled:opacity-50">
        {saving ? '저장 중…' : '정보 저장'}
      </button>
      {message && <p role={message.ok ? 'status' : 'alert'} className={`w-full text-sm ${message.ok ? 'text-emerald-300' : 'text-rose-300'}`}>{message.text}</p>}
    </form>
  )
}
