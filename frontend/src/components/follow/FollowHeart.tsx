import { Heart } from 'lucide-react'

type Props = {
  /** 팔로워 수 */
  count: number
  /** 팔로잉 중이면 채운 하트(`aria-pressed`) */
  following?: boolean
  /** 누를 수 없음(목록을 받는 중 · 요청 중) */
  disabled?: boolean
  /** 없으면 누를 수 없는 `span`(자기 채널 · 복구 전 · 목록 실패) */
  onClick?: () => void
}

/**
 * 팔로우 하트(§6.7 S2 시청 화면 정리 ⑥): 하트 아이콘 + 팔로워 수 하나. 팔로잉이면 채운 빨간 하트, 아니면 선 하트.
 * `aria-label`은 동작("팔로우" / "팔로우 취소"), `title`은 "팔로워 N명". 상태는 `aria-pressed`로 전한다.
 * 어떤 모양을 보일지는 FollowButton이 followView로 정하고, 이 컴포넌트는 그리기만 한다.
 */
export default function FollowHeart({ count, following = false, disabled = false, onClick }: Props) {
  const text = count.toLocaleString('ko-KR')
  const title = `팔로워 ${text}명`
  const content = (
    <>
      <Heart aria-hidden className={`h-5 w-5 ${following ? 'fill-current text-rose-500' : ''}`} />
      <span>{text}</span>
    </>
  )
  if (!onClick) {
    return (
      <span title={title} className="inline-flex items-center gap-1.5 px-2 py-1 text-sm text-secondary">
        <span className="sr-only">팔로워 </span>
        {content}
      </span>
    )
  }
  return (
    <button
      type="button"
      aria-label={following ? '팔로우 취소' : '팔로우'}
      aria-pressed={following}
      title={title}
      disabled={disabled}
      onClick={onClick}
      className="inline-flex items-center gap-1.5 rounded-full px-2 py-1 text-sm text-secondary transition hover:bg-surface hover:text-primary focus-visible:outline-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent"
    >
      {content}
    </button>
  )
}
