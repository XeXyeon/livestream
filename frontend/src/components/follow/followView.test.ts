import { describe, expect, it } from 'vitest'
import { followView } from './followView'
import type { FollowViewInput } from './followView'

const base: FollowViewInput = { isMine: false, ready: true, loggedIn: true, list: { status: 'success', has: false, updatedAt: 100 }, latest: null }

describe('followView', () => {
  it('자기 채널 · 로그인 복구 전에는 수만', () => {
    expect(followView({ ...base, isMine: true })).toEqual({ kind: 'count' })
    expect(followView({ ...base, ready: false, loggedIn: false })).toEqual({ kind: 'count' })
  })
  it('비로그인은 로그인 링크', () => {
    expect(followView({ ...base, loggedIn: false, list: { status: 'pending' } })).toEqual({ kind: 'login' })
  })
  it('목록을 받는 중에는 누를 수 없고, 목록이 실패하면 수만(상세 값으로 되돌아가지 않음)', () => {
    expect(followView({ ...base, list: { status: 'pending' } })).toEqual({ kind: 'loading' })
    expect(followView({ ...base, list: { status: 'error' } })).toEqual({ kind: 'count' })
  })
  it('목록에 있으면 팔로잉', () => {
    expect(followView({ ...base, list: { status: 'success', has: true, updatedAt: 100 } })).toEqual({ kind: 'toggle', following: true })
  })
  it('마지막 응답은 목록을 다시 받기 전까지만 쓴다', () => {
    expect(followView({ ...base, latest: { following: true, at: 200 } })).toEqual({ kind: 'toggle', following: true })
    expect(followView({ ...base, list: { status: 'pending' }, latest: { following: true, at: 200 } })).toEqual({ kind: 'toggle', following: true })
    expect(followView({ ...base, list: { status: 'success', has: false, updatedAt: 300 }, latest: { following: true, at: 200 } })).toEqual({ kind: 'toggle', following: false })
  })
})
