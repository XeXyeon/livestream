import { ApiError } from '../../api/client'
import type { DonationConfirmInput } from '../../api/types'

// 확인(confirm)하지 못한 결제를 탭(sessionStorage)에 남겨 둔다. 결제 쿼리는 주소에서 바로 지우므로,
// 다시 시도 · 로그인하러 갔다 오기 · 새로고침 때 이어서 확인할 수 있게 한다(Toss는 승인했는데 서버만 PENDING인 경우).

const KEY = 'livestream.pendingDonationConfirm'
/** 이보다 오래된 것은 버린다(그 사이 확인 못 한 결제는 마이페이지 · 문의로) */
export const PENDING_TTL_MS = 30 * 60 * 1000

type Storage = Pick<globalThis.Storage, 'getItem' | 'setItem' | 'removeItem'>
const session = (): Storage | undefined => globalThis.sessionStorage

export function savePending(input: DonationConfirmInput, now = Date.now(), storage = session()) {
  try { storage?.setItem(KEY, JSON.stringify({ ...input, savedAt: now })) } catch { /* 저장소를 못 써도 이번 확인은 진행한다 */ }
}

export function clearPending(storage = session()) {
  try { storage?.removeItem(KEY) } catch { /* 무시 */ }
}

export function loadPending(now = Date.now(), storage = session()): DonationConfirmInput | null {
  try {
    const raw = storage?.getItem(KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<DonationConfirmInput & { savedAt: number }>
    const valid = typeof value.paymentKey === 'string' && typeof value.orderId === 'string'
      && typeof value.amount === 'number' && typeof value.savedAt === 'number'
    if (!valid || now - value.savedAt! > PENDING_TTL_MS) {
      storage?.removeItem(KEY)
      return null
    }
    return { paymentKey: value.paymentKey!, orderId: value.orderId!, amount: value.amount! }
  } catch {
    return null
  }
}

/**
 * 서버가 결과를 확정한 실패(금액 불일치 400 · 없는 주문 404 · 이미 처리 409)가 아니면 다시 시도할 수 있다.
 * 서버는 Toss 거절과 결과 미상을 같은 502로 주므로 거절도 다시 시도로 보인다(다시 누르면 409로 정리된다).
 */
export function retryable(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true
  return error.status === 401 || error.status >= 500
}

/** 화면 문구: 서버 계약(§4.4)의 message만 쓴다. fetch 실패(TypeError) 같은 영어 문구는 보이지 않는다 */
export function failureMessage(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}
