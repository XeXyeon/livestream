import { useState } from 'react'
import type { ReactNode } from 'react'
import { Radio } from 'lucide-react'

// 썸네일이 없거나(라이브 시작 직후 · 캡처 실패) 깨지면 기본 그림을 보여 준다.
export default function Thumbnail({ src, alt, children }: { src: string | null; alt: string; children?: ReactNode }) {
  const [failed, setFailed] = useState(false)
  return (
    <div className="relative aspect-video overflow-hidden rounded-xl bg-gradient-to-br from-slate-800 to-indigo-950">
      {src && !failed
        ? <img src={src} alt={alt} loading="lazy" onError={() => setFailed(true)} className="h-full w-full object-cover" />
        : <div className="flex h-full w-full items-center justify-center text-slate-500"><Radio aria-hidden className="h-10 w-10" /></div>}
      {children}
    </div>
  )
}
