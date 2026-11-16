import { describe, expect, it } from 'vitest'
import { preferH264 } from './whip'

const codec = (mimeType: string, sdpFmtpLine?: string) => ({ mimeType, sdpFmtpLine })

describe('preferH264 (§6.5 FE-2#1)', () => {
  it('H.264를 모두 앞으로 옮기고 나머지 순서는 그대로 둔다', () => {
    const list = [
      codec('video/VP8'), codec('video/rtx'), codec('video/H264', 'profile-level-id=42001f'),
      codec('video/VP9'), codec('video/H264', 'profile-level-id=42e01f'), codec('video/AV1'),
      codec('video/red'), codec('video/ulpfec'),
    ]
    expect(preferH264(list).map((item) => `${item.mimeType}${item.sdpFmtpLine ? ` ${item.sdpFmtpLine}` : ''}`)).toEqual([
      'video/H264 profile-level-id=42001f', 'video/H264 profile-level-id=42e01f',
      'video/VP8', 'video/rtx', 'video/VP9', 'video/AV1', 'video/red', 'video/ulpfec',
    ])
  })

  it('대소문자가 달라도 H.264로 본다', () => {
    expect(preferH264([codec('video/VP8'), codec('video/h264')]).map((item) => item.mimeType)).toEqual(['video/h264', 'video/VP8'])
  })

  it('H.264가 없으면 그대로', () => {
    const list = [codec('video/VP8'), codec('video/rtx'), codec('video/VP9')]
    expect(preferH264(list)).toEqual(list)
  })

  it('원본 배열을 바꾸지 않는다', () => {
    const list = [codec('video/VP8'), codec('video/H264')]
    preferH264(list)
    expect(list.map((item) => item.mimeType)).toEqual(['video/VP8', 'video/H264'])
  })
})
