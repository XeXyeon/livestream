import { describe, expect, it } from 'vitest'
import { donationAmountError, donationMessageError, normalizeMessage, parseAmount } from './donationForm'

describe('parseAmount', () => {
  it('쉼표 · 공백 · "원"을 무시하고 숫자만 읽는다', () => {
    expect(parseAmount('3,000원')).toBe(3000)
    expect(parseAmount(' 1500 ')).toBe(1500)
  })

  it('숫자가 아니거나 비면 null', () => {
    expect(parseAmount('')).toBeNull()
    expect(parseAmount('12a')).toBeNull()
    expect(parseAmount('-1000')).toBeNull()
    expect(parseAmount('1.5')).toBeNull()
  })
})

describe('donationAmountError', () => {
  it('1,000원 이상 100,000원 이하만 허용한다(1,000원 단위가 아니어도 된다)', () => {
    expect(donationAmountError(1000)).toBeNull()
    expect(donationAmountError(1234)).toBeNull()
    expect(donationAmountError(100000)).toBeNull()
    expect(donationAmountError(999)).not.toBeNull()
    expect(donationAmountError(100001)).not.toBeNull()
    expect(donationAmountError(null)).not.toBeNull()
  })
})

describe('donationMessageError', () => {
  it('비어도 되고, 앞뒤 공백을 뺀 100자(코드 포인트)까지 허용한다', () => {
    expect(donationMessageError('')).toBeNull()
    expect(donationMessageError(` ${'가'.repeat(100)} `)).toBeNull()
    expect(donationMessageError('😀'.repeat(100))).toBeNull()
    expect(donationMessageError('가'.repeat(101))).not.toBeNull()
  })

  it('제어 문자 · 방향 제어 문자는 막는다(서버와 같은 규칙)', () => {
    expect(donationMessageError('안녕\n하세요')).not.toBeNull()
    expect(donationMessageError('abc‮def')).not.toBeNull()
    expect(donationMessageError('abc⁦def')).not.toBeNull()
  })
})

describe('normalizeMessage', () => {
  it('공백만 있으면 보내지 않는다', () => {
    expect(normalizeMessage('   ')).toBeUndefined()
    expect(normalizeMessage(' 응원해요 ')).toBe('응원해요')
  })
})
