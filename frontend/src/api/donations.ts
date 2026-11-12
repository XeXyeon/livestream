import { apiFetch, toQueryString } from './client'
import type { Donation, DonationConfirmInput, DonationCreateInput, DonationCreated, DonationPage, ReceivedDonations } from './types'

// §4.13. 생성 · confirm은 로그인 필요, 목록은 공개(APPROVED만)

export const createDonation = (broadcastId: number, input: DonationCreateInput) =>
  apiFetch<DonationCreated>(`/broadcasts/${broadcastId}/donations`, { method: 'POST', body: JSON.stringify(input) })

/** 같은 값으로 다시 불러도 같은 응답(서버 멱등). FAILED 주문은 409 DONATION_ALREADY_PROCESSED */
export const confirmDonation = (input: DonationConfirmInput) =>
  apiFetch<Donation>('/donations/confirm', { method: 'POST', body: JSON.stringify(input) })

export const listDonations = (broadcastId: number, page = 0) =>
  apiFetch<DonationPage>(`/broadcasts/${broadcastId}/donations${toQueryString({ page })}`)

/** 내 채널이 받은 후원, 방송별 합계(S8) */
export const getReceivedDonations = () => apiFetch<ReceivedDonations>('/members/me/donations/received')
