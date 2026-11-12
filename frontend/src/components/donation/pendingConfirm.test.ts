import { describe, expect, it } from 'vitest'
import { ApiError } from '../../api/client'
import { PENDING_TTL_MS, clearPending, failureMessage, loadPending, retryable, savePending } from './pendingConfirm'

function memoryStorage() {
  const map = new Map<string, string>()
  return {
    getItem: (key: string) => map.get(key) ?? null,
    setItem: (key: string, value: string) => { map.set(key, value) },
    removeItem: (key: string) => { map.delete(key) },
  }
}

const input = { paymentKey: 'pk', orderId: 'order-1', amount: 5000 }

describe('pending confirm 저장', () => {
  it('저장한 결제를 다시 읽고, 지우면 없다', () => {
    const storage = memoryStorage()
    savePending(input, 1000, storage)
    expect(loadPending(2000, storage)).toEqual(input)
    clearPending(storage)
    expect(loadPending(2000, storage)).toBeNull()
  })

  it('30분이 지나면 버린다', () => {
    const storage = memoryStorage()
    savePending(input, 0, storage)
    expect(loadPending(PENDING_TTL_MS + 1, storage)).toBeNull()
    expect(storage.getItem('livestream.pendingDonationConfirm')).toBeNull()
  })

  it('모양이 틀리거나 깨진 값은 무시한다', () => {
    const storage = memoryStorage()
    storage.setItem('livestream.pendingDonationConfirm', '{"orderId":1}')
    expect(loadPending(0, storage)).toBeNull()
    storage.setItem('livestream.pendingDonationConfirm', '{')
    expect(loadPending(0, storage)).toBeNull()
  })

  it('저장소가 없거나 던져도 멈추지 않는다', () => {
    const broken = { getItem: () => { throw new Error('x') }, setItem: () => { throw new Error('x') }, removeItem: () => { throw new Error('x') } }
    expect(() => savePending(input, 0, broken)).not.toThrow()
    expect(loadPending(0, broken)).toBeNull()
    expect(loadPending(0, undefined)).toBeNull()
  })
})

describe('retryable · failureMessage', () => {
  it('401 · 5xx · 네트워크 오류는 다시 시도, 400 · 404 · 409는 끝', () => {
    expect(retryable(new ApiError(401, 'UNAUTHENTICATED', 'x'))).toBe(true)
    expect(retryable(new ApiError(502, 'PAYMENT_CONFIRM_FAILED', 'x'))).toBe(true)
    expect(retryable(new TypeError('Failed to fetch'))).toBe(true)
    expect(retryable(new ApiError(400, 'DONATION_AMOUNT_MISMATCH', 'x'))).toBe(false)
    expect(retryable(new ApiError(409, 'DONATION_ALREADY_PROCESSED', 'x'))).toBe(false)
  })

  it('서버 message만 쓰고 나머지는 기본 문구', () => {
    expect(failureMessage(new ApiError(409, 'X', '이미 처리된 후원입니다'), '기본')).toBe('이미 처리된 후원입니다')
    expect(failureMessage(new TypeError('Failed to fetch'), '기본')).toBe('기본')
  })
})
