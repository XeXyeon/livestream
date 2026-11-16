import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Heading } from '@astryxdesign/core/Heading'
import { listChannelClips } from '../../api/clips'
import ClipCard from './ClipCard'

const STRIP_SIZE = 12
/** 카드 사이 간격(px). ul의 gap-4와 맞춘다 */
const GAP_PX = 16

/**
 * 시청 화면 아래 "이 채널의 클립" 한 줄. 스크롤바는 숨기고 양옆 ‹ › 버튼으로 카드 한 장씩 넘긴다
 * (트랙패드 · 터치 가로 스크롤은 그대로 된다). 클립이 없거나 못 불러오면 칸째 숨긴다.
 * "전체 보기"는 채널 "클립" 탭(`/channel/{id}?tab=clips`)으로 간다.
 */
export default function ClipStrip({ channelId }: { channelId: number }) {
  const query = useQuery({
    queryKey: ['clips', 'channel-strip', channelId, STRIP_SIZE],
    queryFn: () => listChannelClips(channelId, 0, STRIP_SIZE),
  })
  const items = query.data?.items ?? []
  const listRef = useRef<HTMLUListElement>(null)
  const [canPrev, setCanPrev] = useState(false)
  const [canNext, setCanNext] = useState(false)

  const syncButtons = useCallback(() => {
    const list = listRef.current
    if (!list) return
    // 소수 픽셀 오차로 끝에서 버튼이 남지 않게 1px 여유
    setCanPrev(list.scrollLeft > 1)
    setCanNext(list.scrollLeft + list.clientWidth < list.scrollWidth - 1)
  }, [])

  useEffect(() => {
    const list = listRef.current
    if (!list) return
    syncButtons()
    const observer = new ResizeObserver(syncButtons)
    observer.observe(list)
    return () => observer.disconnect()
  }, [syncButtons, items.length])

  function step(direction: -1 | 1) {
    const list = listRef.current
    const card = list?.querySelector('li')
    if (!list || !card) return
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    list.scrollBy({ left: direction * (card.offsetWidth + GAP_PX), behavior: reduce ? 'auto' : 'smooth' })
  }

  if (items.length === 0) return null

  const headingId = `clip-strip-${channelId}`
  return (
    <section aria-labelledby={headingId} className="space-y-3">
      <div className="flex items-center justify-between gap-4">
        <Heading level={2} id={headingId}>이 채널의 클립</Heading>
        <Link
          to={`/channel/${channelId}?tab=clips`}
          className="inline-flex items-center gap-0.5 rounded-md text-sm font-medium text-secondary hover:text-primary focus-visible:outline-2 focus-visible:outline-accent"
        >
          전체 보기<ChevronRight aria-hidden className="h-4 w-4" />
        </Link>
      </div>
      <div className="relative">
        {/* 스크롤바 숨김. 끝에서 페이지가 같이 넘어가지 않게 overscroll-contain */}
        <ul
          ref={listRef}
          onScroll={syncButtons}
          className="flex snap-x snap-mandatory gap-4 overflow-x-auto overscroll-x-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
        >
          {items.map((clip) => (
            <li key={clip.id} className="w-56 shrink-0 snap-start">
              <ClipCard clip={clip} />
            </li>
          ))}
        </ul>
        {canPrev && <StepButton direction={-1} onClick={() => step(-1)} />}
        {canNext && <StepButton direction={1} onClick={() => step(1)} />}
      </div>
    </section>
  )
}

/** 썸네일(w-56의 16:9 = 126px) 세로 가운데에 걸치는 둥근 버튼 */
function StepButton({ direction, onClick }: { direction: -1 | 1; onClick: () => void }) {
  const Icon = direction < 0 ? ChevronLeft : ChevronRight
  return (
    <button
      type="button"
      aria-label={direction < 0 ? '이전 클립' : '다음 클립'}
      onClick={onClick}
      className={`absolute top-[63px] z-10 flex h-9 w-9 -translate-y-1/2 items-center justify-center rounded-full border border-emphasized bg-surface text-primary shadow-lg shadow-black/40 transition hover:brightness-125 focus-visible:outline-2 focus-visible:outline-accent ${
        direction < 0 ? 'left-0 -translate-x-1/2' : 'right-0 translate-x-1/2'
      }`}
    >
      <Icon aria-hidden className="h-5 w-5" />
    </button>
  )
}
