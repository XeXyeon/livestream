import { useCallback, useEffect, useRef, useState } from 'react'
import type { DonationMessage } from '../../api/types'
import { BannerQueue } from './bannerQueue'

/** 지금 보여 줄 후원(없으면 null)과 넣는 함수. 넣는 쪽은 실시간 donation 메시지만 넣는다(history는 넣지 않음) */
export function useDonationBanner() {
  const [current, setCurrent] = useState<DonationMessage | null>(null)
  const queueRef = useRef<BannerQueue<DonationMessage> | null>(null)

  useEffect(() => {
    const queue = new BannerQueue<DonationMessage>(setCurrent)
    queueRef.current = queue
    return () => {
      queue.dispose()
      if (queueRef.current === queue) queueRef.current = null
    }
  }, [])

  const push = useCallback((donation: DonationMessage) => queueRef.current?.push(donation), [])
  return { current, push }
}
