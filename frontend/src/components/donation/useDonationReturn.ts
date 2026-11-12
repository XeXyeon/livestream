import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { Donation } from '../../api/types'
import { useAuth } from '../../store/auth'
import { clearPending, failureMessage, loadPending, retryable, savePending } from './pendingConfirm'
import { confirmPaymentOnce, parsePaymentReturn, withoutPaymentParams } from './tossCheckout'
import type { PaymentReturn } from './tossCheckout'

export type DonationReturnState =
  | { status: 'idle' }
  | { status: 'confirming' }
  | { status: 'approved'; donation: Donation }
  | { status: 'canceled' }
  /** 로그아웃 상태: 로그인한 뒤 이 화면으로 돌아오면 이어서 확인한다 */
  | { status: 'needsLogin' }
  /** canRetry: 같은 결제로 confirm을 다시 부를 수 있다(서버 502 · 401 · 네트워크 오류) */
  | { status: 'failed'; message: string; canRetry: boolean }

function initialState(ret: PaymentReturn | null): DonationReturnState {
  if (!ret) return { status: 'idle' }
  if (ret.kind === 'success') return { status: 'confirming' }
  if (ret.kind === 'canceled') return { status: 'canceled' }
  return { status: 'failed', message: ret.message, canRetry: false }
}

/**
 * S2 Live: 결제위젯에서 돌아온 주소(`?payment=success|fail…`)를 처리한다.
 * 리다이렉트는 페이지를 새로 여므로 처음 마운트 때의 쿼리만 본다. 쿼리는 바로 지우고(새로고침해도 주소로 다시 confirm하지 않음),
 * 성공이면 로그인 복구(bootstrap)가 끝난 뒤 confirm을 부른다(복구 refresh와 겹치지 않게).
 * 확인하지 못한 결제는 sessionStorage에 30분 남겨, 다시 시도 · 로그인 뒤 복귀 · 새로고침 때 이어서 확인한다
 * (Toss는 승인했는데 서버만 PENDING인 경우를 놓치지 않기 위해).
 */
export function useDonationReturn() {
  const location = useLocation()
  const navigate = useNavigate()
  const authReady = useAuth((state) => state.ready)
  const loggedIn = useAuth((state) => state.member !== null)
  // 처음 마운트 때의 주소만 쓴다: 쿼리를 지운 뒤의 location 변화로 다시 돌지 않는다
  const [initial] = useState(() => {
    const fromUrl = parsePaymentReturn(location.search)
    if (fromUrl?.kind === 'success') savePending(fromUrl)
    const pending = fromUrl ? null : loadPending()
    const ret: PaymentReturn | null = fromUrl ?? (pending ? { kind: 'success', ...pending } : null)
    return { ret, fromUrl: fromUrl !== null, location }
  })
  const { ret } = initial
  const [state, setState] = useState<DonationReturnState>(() => initialState(ret))
  const [attempt, setAttempt] = useState(0)
  // 결과가 정해졌거나(승인 · 다시 시도할 수 없는 실패) 닫은 뒤에는 로그인 상태가 바뀌어도 다시 돌지 않는다
  const settledRef = useRef(false)

  useEffect(() => {
    if (!initial.fromUrl) return
    const { pathname, search, hash } = initial.location
    navigate({ pathname, search: withoutPaymentParams(search), hash }, { replace: true })
  }, [initial, navigate])

  useEffect(() => {
    if (ret?.kind !== 'success' || !authReady || settledRef.current) return
    if (!loggedIn) {
      // 확인 API는 로그인이 필요하다. 매번 401을 받지 않고 로그인을 안내한다(pending은 남긴다)
      setState({ status: 'needsLogin' })
      return
    }
    let active = true
    confirmPaymentOnce(ret).then(
      (donation) => {
        clearPending()
        settledRef.current = true
        if (active) setState({ status: 'approved', donation })
      },
      (error: unknown) => {
        const canRetry = retryable(error)
        if (!canRetry) {
          clearPending()
          settledRef.current = true
        }
        if (active) setState({ status: 'failed', message: failureMessage(error, '후원을 확인하지 못했습니다. 네트워크를 확인한 뒤 다시 확인해 주세요.'), canRetry })
      },
    )
    return () => { active = false }
  }, [ret, authReady, loggedIn, attempt])

  const retry = useCallback(() => {
    if (ret?.kind !== 'success') return
    setState({ status: 'confirming' })
    setAttempt((n) => n + 1)
  }, [ret])

  // 확인 중에는 닫지 않는다(닫아도 결과가 나중에 다시 뜨므로)
  const dismiss = useCallback(() => setState((old) => {
    if (old.status === 'confirming') return old
    settledRef.current = true
    return { status: 'idle' }
  }), [])
  return { state, retry, dismiss }
}
