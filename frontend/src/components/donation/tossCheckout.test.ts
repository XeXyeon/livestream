import { describe, expect, it, vi } from 'vitest'
import type { Donation, DonationCreated } from '../../api/types'
import {
  confirmPaymentOnce,
  openCheckout,
  parsePaymentReturn,
  paymentErrorMessage,
  paymentReturnUrls,
  withoutPaymentParams,
} from './tossCheckout'

const order: DonationCreated = {
  donationId: 7, orderId: 'order-1', amount: 5000, clientKey: 'test_gck_x', orderName: '후원', customerName: '시청자',
}

describe('결제 복귀 주소', () => {
  it('성공 · 실패 주소는 시청 화면으로 돌아온다', () => {
    expect(paymentReturnUrls(14, 'https://localhost')).toEqual({
      successUrl: 'https://localhost/live/14?payment=success',
      failUrl: 'https://localhost/live/14?payment=fail',
    })
  })

  it('성공 쿼리를 confirm 값으로 읽는다', () => {
    expect(parsePaymentReturn('?payment=success&paymentType=NORMAL&paymentKey=pk&orderId=order-1&amount=5000')).toEqual({
      kind: 'success', paymentKey: 'pk', orderId: 'order-1', amount: 5000,
    })
  })

  it('성공인데 값이 빠졌거나 금액이 숫자가 아니면 실패로 본다', () => {
    expect(parsePaymentReturn('?payment=success&orderId=order-1&amount=5000')?.kind).toBe('fail')
    expect(parsePaymentReturn('?payment=success&paymentKey=pk&orderId=order-1&amount=abc')?.kind).toBe('fail')
  })

  it('실패 쿼리는 토스 메시지를 쓰고, 사용자가 취소한 것은 따로 구분한다', () => {
    expect(parsePaymentReturn('?payment=fail&code=REJECT_CARD_COMPANY&message=%EC%B9%B4%EB%93%9C%20%EA%B1%B0%EC%A0%88&orderId=order-1')).toEqual({
      kind: 'fail', code: 'REJECT_CARD_COMPANY', message: '결제를 완료하지 못했습니다(토스페이먼츠: 카드 거절)',
    })
    expect(parsePaymentReturn('?payment=fail&code=X')).toEqual({ kind: 'fail', code: 'X', message: '결제를 완료하지 못했습니다.' })
    const long = parsePaymentReturn(`?payment=fail&code=X&message=${'가'.repeat(300)}`)
    expect(long?.kind === 'fail' && long.message.length).toBeLessThan(120)
    expect(parsePaymentReturn('?payment=fail&code=PAY_PROCESS_CANCELED&message=x')).toEqual({ kind: 'canceled' })
  })

  it('결제에서 돌아온 게 아니면 null', () => {
    expect(parsePaymentReturn('')).toBeNull()
    expect(parsePaymentReturn('?tab=chat')).toBeNull()
  })

  it('결제 쿼리만 지우고 나머지는 남긴다', () => {
    expect(withoutPaymentParams('?payment=success&paymentType=NORMAL&paymentKey=pk&orderId=o&amount=1&tab=chat')).toBe('?tab=chat')
    expect(withoutPaymentParams('?payment=fail&code=X&message=y&orderId=o')).toBe('')
  })
})

describe('paymentErrorMessage', () => {
  it('결제창을 닫은 것은 문구 없이 null', () => {
    expect(paymentErrorMessage(Object.assign(new Error('취소'), { code: 'USER_CANCEL' }))).toBeNull()
  })

  it('그 밖의 오류는 메시지를 쓴다', () => {
    expect(paymentErrorMessage(Object.assign(new Error('카드 정보 오류'), { code: 'INVALID_CARD' }))).toBe('카드 정보 오류')
    expect(paymentErrorMessage('x')).not.toBeNull()
  })
})

function fakeWidgets() {
  const destroyed: string[] = []
  let agreementListener: ((status: { agreedRequiredTerms: boolean }) => void) | undefined
  const widgets = {
    setAmount: vi.fn(async () => {}),
    renderPaymentMethods: vi.fn(async () => ({ destroy: async () => { destroyed.push('methods') } })),
    renderAgreement: vi.fn(async () => ({
      destroy: async () => { destroyed.push('agreement') },
      on: (_event: string, listener: typeof agreementListener) => { agreementListener = listener },
    })),
    requestPayment: vi.fn(async () => {}),
  }
  return { widgets, destroyed, agree: (agreed: boolean) => agreementListener?.({ agreedRequiredTerms: agreed }) }
}

describe('openCheckout', () => {
  it('주문의 클라이언트 키로 위젯을 열고 금액을 주문 금액으로 맞춘다', async () => {
    const { widgets } = fakeWidgets()
    const load = vi.fn(async () => widgets as never)
    await openCheckout(order, 14, { load, origin: 'https://localhost' })
    expect(load).toHaveBeenCalledWith('test_gck_x')
    expect(widgets.setAmount).toHaveBeenCalledWith({ currency: 'KRW', value: 5000 })
  })

  it('결제 수단 · 약관을 그리고, 약관 동의를 알려 주고, 결제는 주문 값과 복귀 주소로 요청한다', async () => {
    const { widgets, agree } = fakeWidgets()
    const checkout = await openCheckout(order, 14, { load: async () => widgets as never, origin: 'https://localhost' })
    const onAgreement = vi.fn()
    await checkout.render({ methods: '#methods', agreement: '#agreement' }, onAgreement)
    expect(widgets.renderPaymentMethods).toHaveBeenCalledWith({ selector: '#methods', variantKey: 'DEFAULT' })
    agree(true)
    expect(onAgreement).toHaveBeenCalledWith(true)

    await checkout.pay()
    expect(widgets.requestPayment).toHaveBeenCalledWith({
      orderId: 'order-1', orderName: '후원', customerName: '시청자',
      successUrl: 'https://localhost/live/14?payment=success', failUrl: 'https://localhost/live/14?payment=fail',
    })
  })

  it('destroy는 그린 위젯을 지우고, 그리는 중에 닫혀도 다 그린 뒤 지운다', async () => {
    const first = fakeWidgets()
    const checkout = await openCheckout(order, 14, { load: async () => first.widgets as never, origin: '' })
    await checkout.render({ methods: '#m', agreement: '#a' })
    await checkout.destroy()
    expect(first.destroyed.sort()).toEqual(['agreement', 'methods'])

    const second = fakeWidgets()
    const racing = await openCheckout(order, 14, { load: async () => second.widgets as never, origin: '' })
    const rendering = racing.render({ methods: '#m', agreement: '#a' })
    await racing.destroy()
    await rendering
    expect(second.destroyed.sort()).toEqual(['agreement', 'methods'])
  })
})

describe('openCheckout render 실패 · 동시 호출', () => {
  it('그리는 중에 두 번 불려도 한 번만 그린다', async () => {
    const { widgets } = fakeWidgets()
    const checkout = await openCheckout(order, 14, { load: async () => widgets as never, origin: '' })
    await Promise.all([checkout.render({ methods: '#m', agreement: '#a' }), checkout.render({ methods: '#m', agreement: '#a' })])
    expect(widgets.renderPaymentMethods).toHaveBeenCalledTimes(1)
    expect(widgets.renderAgreement).toHaveBeenCalledTimes(1)
  })

  it('렌더를 공유해도 약관 알림은 마지막으로 부른 쪽에 간다', async () => {
    const { widgets, agree } = fakeWidgets()
    const checkout = await openCheckout(order, 14, { load: async () => widgets as never, origin: '' })
    const first = vi.fn()
    const second = vi.fn()
    await Promise.all([checkout.render({ methods: '#m', agreement: '#a' }, first), checkout.render({ methods: '#m', agreement: '#a' }, second)])
    agree(false)
    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledWith(false)
  })

  it('하나만 실패하면 그려진 것도 지우고 오류를 던지며, 다시 그릴 수 있다', async () => {
    const { widgets, destroyed } = fakeWidgets()
    const renderAgreement = widgets.renderAgreement
    widgets.renderAgreement = vi.fn().mockRejectedValueOnce(new Error('약관 실패')).mockImplementation(renderAgreement) as never
    const checkout = await openCheckout(order, 14, { load: async () => widgets as never, origin: '' })
    await expect(checkout.render({ methods: '#m', agreement: '#a' })).rejects.toThrow('약관 실패')
    expect(destroyed).toEqual(['methods'])
    await checkout.render({ methods: '#m', agreement: '#a' })
    expect(widgets.renderPaymentMethods).toHaveBeenCalledTimes(2)
  })
})

describe('confirmPaymentOnce', () => {
  const donation = { id: 7 } as Donation

  it('같은 주문은 한 번만 confirm한다', async () => {
    const confirm = vi.fn(async () => donation)
    const input = { paymentKey: 'pk', orderId: 'once-1', amount: 5000 }
    const [a, b] = await Promise.all([confirmPaymentOnce(input, confirm), confirmPaymentOnce(input, confirm)])
    expect(a).toBe(donation)
    expect(b).toBe(donation)
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(confirm).toHaveBeenCalledWith(input)
  })

  it('실패하면 다시 시도할 수 있다', async () => {
    const confirm = vi.fn().mockRejectedValueOnce(new Error('지연')).mockResolvedValueOnce(donation)
    const input = { paymentKey: 'pk', orderId: 'once-2', amount: 5000 }
    await expect(confirmPaymentOnce(input, confirm)).rejects.toThrow('지연')
    await expect(confirmPaymentOnce(input, confirm)).resolves.toBe(donation)
    expect(confirm).toHaveBeenCalledTimes(2)
  })
})
