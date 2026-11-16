import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { useClipStatus } from './useClipStatus'

/**
 * 클립을 만든 뒤 상태 알림(§6.7). 만드는 중 → 준비되면 "클립 보기" 링크, 실패하면 사유 없이 한 줄.
 * 끝나면 내 클립 · 목록 캐시를 새로 받는다.
 */
export default function ClipProgress({ clipId, onDismiss }: { clipId: number; onDismiss: () => void }) {
  const queryClient = useQueryClient()
  const { data, isError } = useClipStatus(clipId)
  const status = data?.status

  useEffect(() => {
    if (status === 'READY' || status === 'FAILED') void queryClient.invalidateQueries({ queryKey: ['clips'] })
  }, [status, queryClient])

  if (isError) return <Banner role="alert" status="error" title="클립 상태를 불러오지 못했습니다." isDismissable onDismiss={onDismiss} />
  if (status === 'READY') {
    return (
      <Banner
        role="status"
        status="success"
        title="클립이 준비됐습니다."
        isDismissable
        onDismiss={onDismiss}
        endContent={<Button href={`/clips/${clipId}`} label="클립 보기" variant="secondary" size="sm" />}
      />
    )
  }
  if (status === 'FAILED') return <Banner role="alert" status="error" title="클립을 만들지 못했습니다." isDismissable onDismiss={onDismiss} />
  return <Banner role="status" status="info" title="클립을 만드는 중…" description="몇 초에서 1분쯤 걸립니다. 이 화면을 떠나도 마이페이지에서 볼 수 있습니다." />
}
