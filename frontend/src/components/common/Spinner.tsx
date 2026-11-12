export default function Spinner({ label = '불러오는 중' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center justify-center gap-2 py-10 text-sm text-slate-400">
      <span aria-hidden className="h-4 w-4 animate-spin rounded-full border-2 border-slate-500 border-t-transparent" />
      {label}
    </div>
  )
}
