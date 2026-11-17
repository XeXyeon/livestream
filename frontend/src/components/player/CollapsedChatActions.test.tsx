import { isValidElement } from 'react'
import type { ReactElement, ReactNode } from 'react'
import { renderToString } from 'react-dom/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CollapsedChatActions from './CollapsedChatActions'

// 스토어는 훅 없이 값만 돌려주는 가짜로 바꿔, 컴포넌트를 함수로 직접 불러 버튼의 onClick을 눌러 본다(DOM 환경 없음)
const state = vi.hoisted(() => ({ member: null as object | null, ready: true, openLogin: vi.fn() }))
vi.mock('../../store/auth', () => ({
  useAuth: (select: (s: { member: object | null; ready: boolean }) => unknown) => select(state),
}))
vi.mock('../../store/authDialog', () => ({
  useAuthDialog: (select: (s: { open: () => void }) => unknown) => select({ open: state.openLogin }),
}))
vi.mock('@astryxdesign/core/Tooltip', () => ({
  Tooltip: ({ content, children }: { content: string; children: ReactNode }) => <span data-tip={content}>{children}</span>,
}))

function findButton(node: ReactNode, label: string): ReactElement<{ onClick: () => void }> | null {
  if (Array.isArray(node)) return node.map((child) => findButton(child, label)).find(Boolean) ?? null
  if (!isValidElement<{ children?: ReactNode; 'aria-label'?: string; onClick: () => void }>(node)) return null
  if (node.props['aria-label'] === label) return node as ReactElement<{ onClick: () => void }>
  return findButton(node.props.children, label)
}
const tree = (props: Parameters<typeof CollapsedChatActions>[0]) => CollapsedChatActions(props)

describe('CollapsedChatActions', () => {
  beforeEach(() => {
    state.member = { id: 1 }
    state.ready = true
    state.openLogin.mockClear()
  })

  it('채팅 · 후원 아이콘이 짧은 명사 툴팁과 함께 그려진다', () => {
    const html = renderToString(<CollapsedChatActions onExpandChat={() => {}} onDonate={() => {}} />)
    expect(html).toMatch(/data-tip="채팅"[^>]*><button[^>]*aria-label="채팅"/)
    expect(html).toMatch(/data-tip="후원"[^>]*><button[^>]*aria-label="후원"/)
  })

  it('채팅을 누르면 펼치기, 후원을 누르면 후원 창(로그인 상태)', () => {
    const expand = vi.fn()
    const donate = vi.fn()
    const element = tree({ onExpandChat: expand, onDonate: donate })
    findButton(element, '채팅')!.props.onClick()
    expect(expand).toHaveBeenCalledTimes(1)
    findButton(element, '후원')!.props.onClick()
    expect(donate).toHaveBeenCalledTimes(1)
    expect(state.openLogin).not.toHaveBeenCalled()
  })

  it('비로그인이 후원을 누르면 로그인 모달만 연다', () => {
    state.member = null
    const donate = vi.fn()
    findButton(tree({ onExpandChat: () => {}, onDonate: donate }), '후원')!.props.onClick()
    expect(donate).not.toHaveBeenCalled()
    expect(state.openLogin).toHaveBeenCalledTimes(1)
  })

  it('종료 · 방송인 본인(onDonate 없음)이거나 인증 확인 전에는 후원 아이콘이 없다', () => {
    expect(renderToString(<CollapsedChatActions onExpandChat={() => {}} />)).not.toContain('후원')
    state.ready = false
    const html = renderToString(<CollapsedChatActions onExpandChat={() => {}} onDonate={() => {}} />)
    expect(html).not.toContain('후원')
    expect(html).toContain('aria-label="채팅"')
  })
})
