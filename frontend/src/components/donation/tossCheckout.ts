import { ANONYMOUS, loadTossPayments } from '@tosspayments/tosspayments-sdk'
import type { TossPaymentsWidgets, WidgetAgreementStatus } from '@tosspayments/tosspayments-sdk'
import { confirmDonation } from '../../api/donations'
import type { Donation, DonationConfirmInput, DonationCreated } from '../../api/types'

// §4.13 2~3 · §9 FE-6: Toss 결제위젯 v2를 리다이렉트 방식으로 쓴다.
// 성공하면 `/live/:channelId?payment=success&paymentKey=&orderId=&amount=`로 돌아와 confirm을 부른다.
// 이 파일은 화면을 모른다: DonateDialog가 위젯을 그릴 자리(selector)를 넘기고, Live가 돌아온 쿼리를 넘긴다.

export type PaymentReturn =
  | ({ kind: 'success' } & DonationConfirmInput)
  | { kind: 'canceled' }
  | { kind: 'fail'; code: string; message: string }

/** 결제 결과 쿼리 키. 돌아온 뒤 주소에서 지운다(새로고침 · 공유 때 다시 confirm하지 않게) */
const PAYMENT_PARAMS = ['payment', 'paymentType', 'paymentKey', 'orderId', 'amount', 'code', 'message']
/** 사용자가 결제창을 닫은 경우. 오류로 보이지 않는다 */
const CANCEL_CODES = new Set(['USER_CANCEL', 'PAY_PROCESS_CANCELED'])
const FAIL_MESSAGE_MAX = 80

export function paymentReturnUrls(channelId: number, origin: string) {
  const base = `${origin}/live/${channelId}`
  return { successUrl: `${base}?payment=success`, failUrl: `${base}?payment=fail` }
}

/** 주소의 쿼리에서 결제 결과를 읽는다. 결제에서 돌아온 게 아니면 null */
export function parsePaymentReturn(search: string): PaymentReturn | null {
  const params = new URLSearchParams(search)
  const payment = params.get('payment')
  if (payment === 'success') {
    const paymentKey = params.get('paymentKey')
    const orderId = params.get('orderId')
    const amount = Number(params.get('amount'))
    if (!paymentKey || !orderId || !Number.isSafeInteger(amount) || amount <= 0) {
      return { kind: 'fail', code: 'INVALID_PAYMENT_RETURN', message: '결제 결과를 확인하지 못했습니다. 후원 내역을 확인해 주세요.' }
    }
    return { kind: 'success', paymentKey, orderId, amount }
  }
  if (payment === 'fail') {
    const code = params.get('code') ?? 'PAYMENT_FAILED'
    if (CANCEL_CODES.has(code)) return { kind: 'canceled' }
    // 주소의 message는 누구나 만들 수 있으므로 출처를 밝히고 길이를 줄인다
    const tossMessage = params.get('message')?.trim().slice(0, FAIL_MESSAGE_MAX)
    return { kind: 'fail', code, message: tossMessage ? `결제를 완료하지 못했습니다(토스페이먼츠: ${tossMessage})` : '결제를 완료하지 못했습니다.' }
  }
  return null
}

/** 결제 결과 쿼리를 뺀 나머지(`?a=1` 또는 빈 문자열) */
export function withoutPaymentParams(search: string): string {
  const params = new URLSearchParams(search)
  for (const key of PAYMENT_PARAMS) params.delete(key)
  const rest = params.toString()
  return rest ? `?${rest}` : ''
}

/** requestPayment가 던진 오류의 화면 문구. 사용자가 결제창을 닫았으면 null */
export function paymentErrorMessage(error: unknown): string | null {
  const code = typeof error === 'object' && error !== null && 'code' in error ? String(error.code) : ''
  if (CANCEL_CODES.has(code)) return null
  return error instanceof Error && error.message ? error.message : '결제를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

type Widgets = Pick<TossPaymentsWidgets, 'setAmount' | 'renderPaymentMethods' | 'renderAgreement' | 'requestPayment'>
type LoadWidgets = (clientKey: string) => Promise<Widgets>

const loadWidgets: LoadWidgets = async (clientKey) =>
  (await loadTossPayments(clientKey)).widgets({ customerKey: ANONYMOUS })

export type CheckoutTargets = { methods: string; agreement: string }

export type Checkout = {
  order: DonationCreated
  /** 결제 수단 · 약관을 그린다. onAgreement는 필수 약관 동의가 바뀔 때마다(결제 버튼 활성화용) */
  render: (targets: CheckoutTargets, onAgreement?: (agreed: boolean) => void) => Promise<void>
  /** 결제창을 연다. 성공 · 실패 모두 리다이렉트로 돌아온다. 결제창을 닫으면 오류로 reject(paymentErrorMessage가 null) */
  pay: () => Promise<void>
  /** 다이얼로그를 닫을 때. 그린 위젯을 지운다 */
  destroy: () => Promise<void>
}

/**
 * 서버가 만든 주문(`POST /broadcasts/{id}/donations` 응답)으로 결제위젯을 준비한다.
 * 금액은 주문 금액으로 고정한다(위젯 금액과 주문 금액이 다르면 confirm이 400 DONATION_AMOUNT_MISMATCH).
 */
export async function openCheckout(
  order: DonationCreated,
  channelId: number,
  { load = loadWidgets, origin = globalThis.location?.origin ?? '' }: { load?: LoadWidgets; origin?: string } = {},
): Promise<Checkout> {
  const widgets = await load(order.clientKey)
  await widgets.setAmount({ currency: 'KRW', value: order.amount })
  const rendered: { destroy: () => Promise<void> }[] = []
  let rendering: Promise<void> | null = null
  let destroyed = false
  // 렌더를 공유해도 약관 알림은 마지막으로 render를 부른 쪽에 간다(StrictMode에서 첫 effect의 콜백은 이미 정리됨)
  let onAgreement: ((agreed: boolean) => void) | undefined

  const renderOnce = async (targets: CheckoutTargets) => {
    const [methods, agreement] = await Promise.allSettled([
      widgets.renderPaymentMethods({ selector: targets.methods, variantKey: 'DEFAULT' }),
      widgets.renderAgreement({ selector: targets.agreement, variantKey: 'AGREEMENT' }),
    ])
    for (const result of [methods, agreement]) if (result.status === 'fulfilled') rendered.push(result.value)
    if (methods.status === 'rejected' || agreement.status === 'rejected') {
      // 하나만 그려진 채로 남기지 않는다. 다시 render할 수 있게 비운다
      await Promise.allSettled(rendered.splice(0).map((widget) => widget.destroy()))
      rendering = null
      throw methods.status === 'rejected' ? methods.reason : (agreement as PromiseRejectedResult).reason
    }
    if (destroyed) {
      await Promise.allSettled(rendered.map((widget) => widget.destroy()))
      return
    }
    // 약관 동의는 바뀔 때만 알려 준다(처음 상태를 조회하는 API가 없다). 화면은 처음에 동의로 두고,
    // 필수 약관에 동의하지 않은 채 결제하면 requestPayment가 오류를 던져 그 문구를 보여 준다.
    agreement.value.on('agreementStatusChange', (status: WidgetAgreementStatus) => onAgreement?.(status.agreedRequiredTerms))
  }

  return {
    order,
    // 그리는 중에 다시 불려도(StrictMode 이중 effect 등) 한 번만 그린다
    render: (targets, listener) => {
      if (destroyed) return Promise.resolve()
      onAgreement = listener
      rendering ??= renderOnce(targets)
      return rendering
    },
    pay: async () => {
      await widgets.requestPayment({
        orderId: order.orderId,
        orderName: order.orderName,
        customerName: order.customerName,
        ...paymentReturnUrls(channelId, origin),
      })
    },
    destroy: async () => {
      if (destroyed) return
      destroyed = true
      await Promise.allSettled(rendered.map((widget) => widget.destroy()))
    },
  }
}

const confirming = new Map<string, Promise<Donation>>()

/**
 * 돌아온 결제를 confirm한다. 같은 주문은 한 번만 부른다(StrictMode 이중 effect · 다시 마운트).
 * 실패하면 기억을 지워 사용자가 다시 시도할 수 있게 한다(서버가 멱등이라 다시 불러도 안전).
 */
export function confirmPaymentOnce(
  input: DonationConfirmInput,
  confirm: (input: DonationConfirmInput) => Promise<Donation> = confirmDonation,
): Promise<Donation> {
  const existing = confirming.get(input.orderId)
  if (existing) return existing
  const request = confirm({ paymentKey: input.paymentKey, orderId: input.orderId, amount: input.amount })
  confirming.set(input.orderId, request)
  request.catch(() => confirming.delete(input.orderId))
  return request
}
