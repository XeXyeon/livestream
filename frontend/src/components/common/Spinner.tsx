import { Spinner as AstryxSpinner } from '@astryxdesign/core/Spinner'

export default function Spinner({ label = '불러오는 중' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center justify-center gap-2 py-10 text-sm text-secondary">
      <AstryxSpinner size="sm" shade="subtle" aria-hidden />
      {label}
    </div>
  )
}
