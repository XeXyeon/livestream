import { renderToString } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import type { Page, RecordingSummary } from '../../api/types'
import RecordingStrip, { RECORDING_STRIP_SIZE } from './RecordingStrip'

// 테스트 환경이 node(DOM 없음)라 캐시에 목록을 미리 넣고 서버 렌더 결과로 확인한다.
const recording = (id: number, broadcastId: number): RecordingSummary => ({
  id, broadcastId, title: `방송 ${id}`, category: { code: 'GAME', label: '게임' }, thumbnailUrl: null,
  durationSeconds: 600, startedAt: '2026-11-16T02:00:00Z', channel: { id: 17, name: '게임하는곰' }, commentCount: 0,
})

function render(items: RecordingSummary[] | null, currentBroadcastId = 99) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  if (items) {
    const page: Page<RecordingSummary> = { items, page: 0, size: RECORDING_STRIP_SIZE, totalElements: items.length, hasNext: false }
    client.setQueryData(['channel', 17, 'recordings-strip', RECORDING_STRIP_SIZE], page)
  }
  return renderToString(
    <QueryClientProvider client={client}>
      <MemoryRouter><RecordingStrip channelId={17} currentBroadcastId={currentBroadcastId} /></MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RecordingStrip 이 채널의 지난 동영상', () => {
  it('다시보기 카드를 한 줄로 그리고 전체 보기는 채널 다시보기 탭으로 간다', () => {
    const html = render([recording(1, 10), recording(2, 11)])
    expect(html).toContain('이 채널의 지난 동영상')
    expect(html).toContain('href="/channel/17?tab=recordings"')
    expect(html).toContain('href="/videos/1"')
    expect(html).toContain('href="/videos/2"')
    expect(html).toContain('overflow-x-auto')
  })

  it('지금 보고 있는 방송의 녹화는 뺀다', () => {
    const html = render([recording(1, 10), recording(2, 11)], 11)
    expect(html).toContain('href="/videos/1"')
    expect(html).not.toContain('href="/videos/2"')
  })

  it('항목이 없거나 아직 못 받았으면 줄째 그리지 않는다', () => {
    expect(render([])).toBe('')
    expect(render([recording(2, 11)], 11)).toBe('')
    expect(render(null)).toBe('')
  })
})
