import { useState } from 'react'
import { Check, Copy, Eye, EyeOff } from 'lucide-react'

type Props = { label: string; value: string; secret?: boolean; hint?: string }

async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

// 읽기 전용 값 + 복사 버튼. secret이면 기본으로 가리고 "보기"로 연다(복사는 가린 채로도 된다).
export default function CopyField({ label, value, secret = false, hint }: Props) {
  const [revealed, setRevealed] = useState(!secret)
  const [copied, setCopied] = useState<'ok' | 'fail' | null>(null)

  async function copy() {
    setCopied((await copyText(value)) ? 'ok' : 'fail')
    setTimeout(() => setCopied(null), 1500)
  }

  return (
    <div className="space-y-1">
      <span className="block text-sm font-medium text-slate-200">{label}</span>
      <div className="flex gap-2">
        <input readOnly aria-label={label} type={revealed ? 'text' : 'password'} value={value} onFocus={(event) => event.target.select()} className="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 font-mono text-sm text-white" />
        {secret && (
          <button type="button" aria-label={revealed ? `${label} 가리기` : `${label} 보기`} onClick={() => setRevealed(!revealed)} className="rounded-lg border border-slate-700 px-2.5 text-slate-300 hover:border-slate-500">
            {revealed ? <EyeOff aria-hidden className="h-4 w-4" /> : <Eye aria-hidden className="h-4 w-4" />}
          </button>
        )}
        <button type="button" onClick={() => void copy()} className="flex items-center gap-1 rounded-lg border border-slate-700 px-3 text-sm text-slate-200 hover:border-slate-500">
          {copied === 'ok' ? <Check aria-hidden className="h-4 w-4 text-emerald-400" /> : <Copy aria-hidden className="h-4 w-4" />}
          {copied === 'ok' ? '복사됨' : copied === 'fail' ? '복사 실패' : '복사'}
        </button>
      </div>
      {hint && <p className="text-xs text-slate-500">{hint}</p>}
    </div>
  )
}
