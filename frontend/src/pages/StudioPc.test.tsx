import { renderToString } from 'react-dom/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import StudioPc from './StudioPc'

const session = vi.hoisted(() => ({ current: null as unknown }))

vi.mock('../components/studio/useBroadcastSession', () => ({ useBroadcastSession: () => session.current }))
vi.mock('../components/chat/useBroadcastChat', () => ({
  useBroadcastChat: () => ({ entries: [], connection: 'connected', chatError: null, send: () => true, clearError: () => {} }),
}))
vi.mock('../components/layout/usePageMeta', () => ({ usePageMeta: () => {} }))

function sessionState(broadcastId: number | null) {
  return {
    phase: broadcastId === null ? 'idle' : 'live',
    active: broadcastId !== null,
    error: null,
    channel: { isPending: false, isError: false, data: { id: 7, title: '내 방송', category: { code: 'GAME' } } },
    broadcastId,
    viewerCount: null,
    start: async () => {},
    end: async () => {},
    saveInfo: async () => {},
    replaceTrack: async () => {},
  }
}

// React가 텍스트 사이에 넣는 주석 노드는 지운다
const render = () => renderToString(<StudioPc />).replaceAll('<!-- -->', '')

// 왼쪽 열 안의 요소들이 나오는 순서를 확인하려고 문자열 위치를 쓴다
const at = (html: string, text: string) => {
  const index = html.indexOf(text)
  expect(index, `"${text}"가 없다`).toBeGreaterThanOrEqual(0)
  return index
}

describe('StudioPc 배치(FE-13)', () => {
  beforeEach(() => {
    vi.stubGlobal('navigator', { mediaDevices: { getDisplayMedia: () => {}, addEventListener: () => {}, removeEventListener: () => {} } })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('소리 2열은 같은 구조의 select 둘(마이크 · 시스템 소리)이고 체크박스는 없다', () => {
    session.current = sessionState(null)
    const html = render()
    expect(html.match(/<select/g)).toHaveLength(3) // 마이크 · 시스템 소리 + 방송 카테고리
    expect(html).toContain('마이크 사용 안 함')
    expect(html).toContain('탭/시스템 소리 포함')
    expect(html).toContain('포함 안 함')
    expect(html).not.toContain('type="checkbox"')
    expect(html).toContain('마이크 볼륨 100%')
    expect(html).toContain('시스템 소리 볼륨 100%')
    expect(html).toContain('aria-label="마이크 음량"')
    expect(html).toContain('aria-label="시스템 소리 음량"')
  })

  it('방송 전 오른쪽 열은 채팅과 같은 틀의 빈 카드 하나다', () => {
    session.current = sessionState(null)
    const html = render()
    expect(html).toContain('방송을 시작하면 채팅이 열립니다.')
    expect(html.match(/<aside aria-label="채팅" class="([^"]*)"/)?.[1]).toContain('lg:sticky lg:top-24 lg:h-[calc(100dvh-6rem)]')
  })

  it('송출 중에는 ChatPanel이 같은 높이 규칙으로 들어가고 빈 카드 문구는 없다', () => {
    session.current = sessionState(1)
    const html = render()
    expect(html).not.toContain('방송을 시작하면 채팅이 열립니다.')
    expect(html).toContain('채팅 메시지')
    expect(html).toContain('lg:sticky lg:top-24 lg:h-[calc(100dvh-6rem)]')
  })

  it('왼쪽 순서: 제목 줄 → 미리보기 → 소스 → 방송, 오른쪽 채팅이 그 뒤', () => {
    session.current = sessionState(null)
    const html = render()
    const order = [
      at(html, 'PC 스튜디오'),
      at(html, '화면 공유를 시작하면 미리보기가 나옵니다.'),
      at(html, 'aria-label="소스"'),
      at(html, 'aria-label="방송"'),
      at(html, '송출 시작'),
      at(html, '방송을 시작하면 채팅이 열립니다.'),
    ]
    expect(order).toEqual([...order].sort((a, b) => a - b))
  })
})
