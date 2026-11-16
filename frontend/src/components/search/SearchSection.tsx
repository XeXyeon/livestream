import type { ReactNode } from 'react'
import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'

type Props = {
  title: string
  /** 조건에 맞는 전체 수 */
  total: number
  /** 지금 보이는 개수. total이 더 크면 "더 보기"를 보인다 */
  shown: number
  /** "더 보기"가 가는 종류별 목록 주소 */
  moreHref: string
  /** 카드 격자의 열 설정(Tailwind 클래스) */
  gridClassName: string
  children: ReactNode
}

/** 통합 검색 요약의 한 절: 제목 + 전체 수, 카드 격자, 더 있으면 "더 보기 (N)". 결과가 없는 절은 그리지 않는다(§6.7 S12) */
export default function SearchSection({ title, total, shown, moreHref, gridClassName, children }: Props) {
  if (total === 0) return null
  return (
    <section aria-label={title}>
      <Heading level={2}>
        {title} <span className="text-base font-normal text-secondary">{total.toLocaleString('ko-KR')}</span>
      </Heading>
      <div className={`mt-4 grid gap-6 ${gridClassName}`}>{children}</div>
      {total > shown && (
        <div className="mt-6 text-center">
          <Button href={moreHref} label={`${title} 더 보기 (${total.toLocaleString('ko-KR')})`} variant="secondary" />
        </div>
      )}
    </section>
  )
}
