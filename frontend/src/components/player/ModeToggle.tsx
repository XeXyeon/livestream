import type { PlayerMode } from '../../api/types'

const OPTIONS: { mode: PlayerMode; label: string; title: string }[] = [
  { mode: 'whep', label: '기본', title: '서버에서 바로 받는 저지연 재생(WebRTC)' },
  { mode: 'grid', label: '그리드', title: '시청자끼리 영상 조각을 나눠 받아 서버 부담을 줄이는 재생(지연이 몇 초 늘어남)' },
]

export default function ModeToggle({ mode, onChange }: { mode: PlayerMode; onChange: (mode: PlayerMode) => void }) {
  return (
    <div role="group" aria-label="재생 모드" className="inline-flex rounded-md border border-border bg-surface p-1 text-sm">
      {OPTIONS.map((option) => (
        <button
          key={option.mode}
          type="button"
          title={option.title}
          aria-pressed={mode === option.mode}
          onClick={() => onChange(option.mode)}
          className={`rounded-sm px-3 py-1 font-medium ${mode === option.mode ? 'bg-accent-bg text-on-accent' : 'text-secondary hover:text-primary'}`}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}
