import { Volume2 } from 'lucide-react'

export default function UnmuteButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="absolute left-3 top-3 z-10 flex items-center gap-1.5 rounded-lg bg-black/70 px-3 py-2 text-sm font-semibold text-white hover:bg-black/90">
      <Volume2 aria-hidden className="h-4 w-4" />소리 켜기
    </button>
  )
}
