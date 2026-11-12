export type Member = {
  id: number
  nickname: string
  channelId: number
}

export type AuthResponse = {
  accessToken: string
  member: Member
}

export type SignupResponse = { memberId: number }

export type ApiErrorResponse = { code: string; message: string }
