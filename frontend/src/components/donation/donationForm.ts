// S2 DonateDialog 입력 검사. 서버(DonationService.amount · message)와 같은 규칙이라 서버까지 가지 않고 막는다.

export const DONATION_PRESETS = [1000, 5000, 10000] as const
export const DONATION_MIN = 1000
export const DONATION_MAX = 100000
export const DONATION_MESSAGE_MAX = 100

// 제어 문자 · 줄/문단 구분자 · 방향 제어 문자(서버 FORBIDDEN_CHARS와 같다)
const FORBIDDEN_CHARS = /[\p{Cc}\u2028\u2029\u202A-\u202E\u2066-\u2069]/u

/** 직접 입력한 금액. 쉼표 · 공백 · "원"은 무시하고, 숫자가 아니면 null */
export function parseAmount(input: string): number | null {
  const digits = input.replace(/[\s,원]/g, '')
  if (!/^\d+$/.test(digits)) return null
  const value = Number(digits)
  return Number.isSafeInteger(value) ? value : null
}

export function donationAmountError(amount: number | null): string | null {
  if (amount === null) return '후원 금액을 숫자로 입력해 주세요.'
  if (amount < DONATION_MIN || amount > DONATION_MAX) {
    return `후원 금액은 ${DONATION_MIN.toLocaleString('ko-KR')}원 이상 ${DONATION_MAX.toLocaleString('ko-KR')}원 이하로 입력해 주세요.`
  }
  return null
}

/** 메시지는 선택. 앞뒤 공백을 뺀 100자(코드 포인트)까지 */
export function donationMessageError(message: string): string | null {
  const value = message.trim()
  if (FORBIDDEN_CHARS.test(value)) return '메시지에 사용할 수 없는 문자가 있습니다.'
  if ([...value].length > DONATION_MESSAGE_MAX) return `메시지는 ${DONATION_MESSAGE_MAX}자까지 쓸 수 있습니다.`
  return null
}

/**
 * 서버로 보낼 메시지: 비었으면 보내지 않는다. 화면은 반드시 이 값을 보낸다.
 * JS trim()과 서버 Java strip()은 지우는 공백이 조금 다르다(NBSP · U+FEFF는 trim만, U+001C~001F는 strip만).
 * 이 값을 보내면 실제로 어긋나지 않고, 끝에 U+001F가 붙은 경우만 화면 검사가 더 엄격하다.
 */
export function normalizeMessage(message: string): string | undefined {
  const value = message.trim()
  return value ? value : undefined
}
