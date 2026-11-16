import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ClipDraftDetail } from '../../api/types'
import { LiveDraftStage } from './ClipDialog'

// 테스트 환경이 node(DOM 없음)라 서버 렌더 결과로 라이브 모드의 상태별 화면을 확인한다.
const base: ClipDraftDetail = {
  id: 3, broadcastId: 1, status: 'PENDING', windowStartSeconds: 30, windowSeconds: 120, videoUrl: null, createdAt: '2026-11-16T02:00:00Z',
}
const render = (draft: ClipDraftDetail | null, isError = false) => renderToString(
  <LiveDraftStage draft={draft} isError={isError} defaultTitle="오늘의 방송" onClose={() => {}} onCreated={() => {}} />,
)

describe('ClipDialog 라이브 모드(LiveDraftStage)', () => {
  it('PENDING · 아직 응답 전이면 준비 중 안내만 그린다', () => {
    for (const html of [render(base), render(null)]) {
      expect(html).toContain('최근 2분을 준비하는 중…')
      expect(html).not.toContain('<video')
      expect(html).not.toContain('type="range"')
    }
  })

  it('READY면 초안 영상과 구간 선택(기본: 끝 30초)을 그린다', () => {
    const html = render({ ...base, status: 'READY', videoUrl: '/api/clip-drafts/3/video?token=t' })
    expect(html).toContain('src="/api/clip-drafts/3/video?token=t"')
    expect(html).toContain('value="1:30"') // 120초 초안의 마지막 30초
    expect(html).toContain('type="range"')
    expect(html).toContain('step="1"')
    expect(html).toContain('value="오늘의 방송"')
    expect(html).toContain('1:30 ~ 2:00 구간을 클립으로 만듭니다.')
  })

  it('짧은 초안이면 길이 기본값도 초안 길이로 줄인다', () => {
    const html = render({ ...base, status: 'READY', windowSeconds: 12, videoUrl: '/v' })
    expect(html).toContain('value="0:00"')
    expect(html).toContain('0:00 ~ 0:12 구간을 클립으로 만듭니다.')
  })

  it('FAILED · 조회 실패면 안내와 닫기만 그린다', () => {
    for (const html of [render({ ...base, status: 'FAILED' }), render(base, true)]) {
      expect(html).toContain('클립을 만들 수 없습니다. 잠시 뒤 다시 시도해 주세요.')
      expect(html).not.toContain('<video')
      expect(html).not.toContain('준비하는 중')
    }
  })
})

describe('ClipDialog 구간 막대(CLIP-4)', () => {
  it('READY 초안이면 막대 손잡이 · 시작 입력 · 길이 슬라이더가 같은 구간(1:30 ~ 2:00)을 보인다', () => {
    const html = render({ ...base, status: 'READY', videoUrl: '/v' })
    expect(html).toMatch(/role="slider"[^>]*aria-label="클립 시작"[^>]*aria-valuenow="90"/)
    expect(html).toMatch(/role="slider"[^>]*aria-label="클립 끝"[^>]*aria-valuenow="120"/)
    expect(html).toContain('value="1:30"')
    expect(html).toMatch(/type="range"[^>]*value="30"/)
    expect(html).toContain('구간 재생')
  })

  it('5초보다 짧은 초안이면 막대 없이 입력만', () => {
    const html = render({ ...base, status: 'READY', windowSeconds: 4, videoUrl: '/v' })
    expect(html).not.toContain('role="slider"')
    expect(html).toContain('type="range"')
  })
})

describe('ClipDialog 닫기 버튼', () => {
  it('준비 중 · 실패 · 구간 선택 화면 모두 제목 줄에 "닫기" 버튼이 있다', () => {
    for (const html of [render(base), render(base, true), render({ ...base, status: 'READY', videoUrl: '/v' })]) {
      expect(html).toContain('aria-label="닫기"')
    }
  })
})
