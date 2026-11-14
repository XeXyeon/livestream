import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { createPortal } from 'react-dom'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Dialog, DialogHeader } from '@astryxdesign/core/Dialog'
import { ApiError } from '../../api/client'
import { createDonation } from '../../api/donations'
import TextField from '../common/TextField'
import {
  DONATION_MAX,
  DONATION_MESSAGE_MAX,
  DONATION_MIN,
  DONATION_PRESETS,
  donationAmountError,
  donationMessageError,
  normalizeMessage,
  parseAmount,
} from './donationForm'
import { openCheckout, paymentErrorMessage } from './tossCheckout'
import type { Checkout } from './tossCheckout'

const METHODS_ID = 'donate-payment-methods'
const AGREEMENT_ID = 'donate-agreement'
const won = (amount: number) => `${amount.toLocaleString('ko-KR')}원`

type Props = { broadcastId: number; channelId: number; isOpen: boolean; onOpenChange: (open: boolean) => void }

/**
 * §6.7 S2 후원: 금액(1,000/5,000/10,000 + 직접 입력) · 메시지 → 서버 주문 → Toss 결제위젯 → 리다이렉트로 돌아와 confirm(useDonationReturn).
 * 1단계(금액 · 메시지)는 ASTRYX Dialog. 2단계(결제위젯)는 Dialog 밖의 고정 패널이다: Dialog는 네이티브 showModal(top layer)이라
 * Toss가 body에 붙이는 결제창이 그 아래에 깔리고 inert가 된다.
 */
export default function DonateDialog({ broadcastId, channelId, isOpen, onOpenChange }: Props) {
  const [preset, setPreset] = useState<number | null>(DONATION_PRESETS[0])
  const [custom, setCustom] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [checkout, setCheckout] = useState<Checkout | null>(null)

  const amount = preset ?? parseAmount(custom)

  const close = () => {
    if (creating) return // 주문을 만드는 중에는 닫지 않는다(닫은 뒤 결제 패널이 뜨지 않게)
    setError('')
    onOpenChange(false)
  }

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const problem = donationAmountError(amount) ?? donationMessageError(message)
    if (problem || amount === null) {
      setError(problem ?? '')
      return
    }
    setError('')
    setCreating(true)
    try {
      const order = await createDonation(broadcastId, { amount, message: normalizeMessage(message) })
      setCheckout(await openCheckout(order, channelId))
      onOpenChange(false)
    } catch (e) {
      // 서버 message만 보인다. 네트워크 · SDK 로드 실패는 기본 문구
      setError(e instanceof ApiError ? e.message : '후원을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setCreating(false)
    }
  }

  const leaveCheckout = () => {
    void checkout?.destroy()
    setCheckout(null)
  }

  return (
    <>
      <Dialog isOpen={isOpen} onOpenChange={(open) => (open ? onOpenChange(true) : close())} purpose={creating ? 'required' : 'form'} width={440}>
        <DialogHeader title="후원하기" subtitle="결제는 토스페이먼츠 테스트 결제로 진행됩니다." onOpenChange={(open) => !open && close()} />
        <form onSubmit={submit} className="space-y-5 pt-2" noValidate>
          <fieldset>
            <legend className="text-sm font-medium text-primary">금액</legend>
            <div className="mt-2 flex flex-wrap gap-2">
              {DONATION_PRESETS.map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={preset === value}
                  onClick={() => { setPreset(value); setCustom(''); setError('') }}
                  className={`rounded-full border px-4 py-1.5 text-sm font-medium ${
                    preset === value ? 'border-transparent bg-accent-bg text-on-accent' : 'border-border text-primary hover:border-border-strong'
                  }`}
                >
                  {won(value)}
                </button>
              ))}
            </div>
            <div className="mt-3">
              <TextField
                label="직접 입력"
                inputMode="numeric"
                placeholder={`${won(DONATION_MIN)} ~ ${won(DONATION_MAX)}`}
                value={custom}
                onChange={(event) => { setCustom(event.target.value); setPreset(null); setError('') }}
                hint="1,000원 단위가 아니어도 됩니다."
              />
            </div>
          </fieldset>
          <TextField
            label={`메시지 (선택, ${DONATION_MESSAGE_MAX}자)`}
            value={message}
            maxLength={DONATION_MESSAGE_MAX * 2}
            onChange={(event) => { setMessage(event.target.value); setError('') }}
            placeholder="방송인에게 남길 말"
          />
          {error && <Banner role="alert" status="error" title={error} />}
          <div className="flex justify-end gap-2">
            <Button type="button" label="취소" variant="secondary" onClick={close} isDisabled={creating} />
            <Button
              type="submit"
              label={amount !== null && donationAmountError(amount) === null ? `${won(amount)} 결제하기` : '결제하기'}
              variant="primary"
              isLoading={creating}
            />
          </div>
        </form>
      </Dialog>
      {checkout && <CheckoutPanel checkout={checkout} onClose={leaveCheckout} />}
    </>
  )
}

/** 2단계: 결제 수단 · 약관 위젯과 결제 버튼. 네이티브 dialog가 아닌 고정 패널(위 설명) */
function CheckoutPanel({ checkout, onClose }: { checkout: Checkout; onClose: () => void }) {
  const [ready, setReady] = useState(false)
  const [agreed, setAgreed] = useState(true)
  const [paying, setPaying] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    checkout.render({ methods: `#${METHODS_ID}`, agreement: `#${AGREEMENT_ID}` }, (value) => { if (active) setAgreed(value) }).then(
      () => { if (active) setReady(true) },
      (e: unknown) => { if (active) setError(paymentErrorMessage(e) ?? '결제 수단을 불러오지 못했습니다.') },
    )
    return () => { active = false }
  }, [checkout])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && !paying) onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, paying])

  const pay = async () => {
    setError('')
    setPaying(true)
    try {
      await checkout.pay() // 성공 · 실패 모두 리다이렉트로 페이지를 떠난다
    } catch (e) {
      const text = paymentErrorMessage(e)
      if (text) setError(text)
      setPaying(false)
    }
  }

  return createPortal(
    <div className="fixed inset-0 z-40 flex items-end justify-center bg-black/60 sm:items-center sm:p-4">
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="donate-checkout-title"
        className="flex max-h-[92vh] w-full max-w-lg flex-col rounded-t-xl border border-border bg-surface sm:rounded-xl"
      >
        <header className="flex items-center justify-between border-b border-border px-5 py-3">
          <h2 id="donate-checkout-title" className="text-base font-semibold text-primary">{won(checkout.order.amount)} 후원 결제</h2>
          <Button label="닫기" variant="ghost" size="sm" onClick={onClose} isDisabled={paying} />
        </header>
        {/* Toss 위젯은 흰 배경 iframe이라 밝은 칸 안에 그린다 */}
        <div className="min-h-0 flex-1 overflow-y-auto bg-white">
          <div id={METHODS_ID} />
          <div id={AGREEMENT_ID} />
          {!ready && !error && <p className="py-10 text-center text-sm text-neutral-500">결제 수단을 불러오는 중…</p>}
        </div>
        <footer className="space-y-3 border-t border-border px-5 py-3">
          {error && <Banner role="alert" status="error" title={error} />}
          <Button
            label={`${won(checkout.order.amount)} 결제하기`}
            variant="primary"
            width="100%"
            onClick={pay}
            isDisabled={!ready || !agreed}
            isLoading={paying}
          />
        </footer>
      </section>
    </div>,
    document.body,
  )
}
