import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import type { ClipDraftDetail, RecordingDetail } from '../../api/types'
import { LiveDraftStage, RecordingClipForm } from './ClipDialog'

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
    expect(html).toContain('value="1:30"') // 120초 초안의 마지막 30초: 시작
    expect(html).toContain('value="2:00"') // 끝
    expect(html).toContain('value="오늘의 방송"')
    expect(html).toContain('1:30 ~ 2:00 · 30초')
  })

  it('짧은 초안이면 길이 기본값도 초안 길이로 줄인다', () => {
    const html = render({ ...base, status: 'READY', windowSeconds: 12, videoUrl: '/v' })
    expect(html).toContain('value="0:00"')
    expect(html).toContain('0:00 ~ 0:12 · 12초')
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
  it('READY 초안이면 막대 손잡이 · 시작 · 끝 입력이 같은 구간(1:30 ~ 2:00)을 보인다', () => {
    const html = render({ ...base, status: 'READY', videoUrl: '/v' })
    expect(html).toMatch(/role="slider"[^>]*aria-label="클립 시작"[^>]*aria-valuenow="90"/)
    expect(html).toMatch(/role="slider"[^>]*aria-label="클립 끝"[^>]*aria-valuenow="120"/)
    expect(html).toContain('value="1:30"')
    expect(html).toContain('value="2:00"')
    expect(html).toContain('구간 재생')
  })

  it('5초보다 짧은 초안이면 막대 없이 입력만', () => {
    const html = render({ ...base, status: 'READY', windowSeconds: 4, videoUrl: '/v' })
    expect(html).not.toContain('role="slider"')
    expect(html).toContain('끝 위치')
  })
})

describe('ClipDialog 닫기 버튼', () => {
  it('준비 중 · 실패 · 구간 선택 화면 모두 제목 줄에 "닫기" 버튼이 있다', () => {
    for (const html of [render(base), render(base, true), render({ ...base, status: 'READY', videoUrl: '/v' })]) {
      expect(html).toContain('aria-label="닫기"')
    }
  })
})

const recording: RecordingDetail = {
  id: 7, broadcastId: 1, title: '지난 방송', category: { code: 'GAME', label: '게임' }, thumbnailUrl: null, durationSeconds: 3600,
  startedAt: '2026-11-16T02:00:00Z', channel: { id: 1, name: '채널' }, commentCount: 0,
  status: 'READY', visibility: 'PUBLIC', videoUrl: '/vod/a.mp4', fileDeleted: false, donationTotal: 0, isOwner: false,
}
const renderRecording = (initialStart: number) => renderToString(
  <RecordingClipForm recording={recording} initialStart={initialStart} onClose={() => {}} onCreated={() => {}} />,
)

describe('ClipDialog 다시보기 모드(CLIP-4#1: 라이브와 같은 구조)', () => {
  it('미리보기 영상 · 구간 막대 · 구간 재생 · 입력을 라이브 모드와 같이 그린다', () => {
    const live = render({ ...base, status: 'READY', videoUrl: '/v' })
    const html = renderRecording(600)
    expect(html).toContain('src="/vod/a.mp4"')
    for (const part of ['role="slider"', '구간 재생', '시작 위치', '끝 위치', '최대 60초까지', '클립 공개', 'aria-label="닫기"']) {
      expect(html).toContain(part)
      expect(live).toContain(part)
    }
    expect(html).toContain('value="10:00"')
    expect(html).toContain('value="지난 방송"')
  })

  it('긴 영상은 막대가 확대 창(4분)만 보인다', () => {
    const html = renderRecording(600)
    expect(html).toContain('<span>9:00</span><span>13:00</span>')
    expect(html).toMatch(/aria-label="클립 시작"[^>]*aria-valuenow="600"/)
  })
})

describe('ClipDialog 구간 입력: 시작 · 끝 위치 + 60초 안내', () => {
  it('두 모드 모두 길이 · 시작 슬라이더 없이 시작 · 끝 입력과 5~60초 안내만 둔다', () => {
    for (const html of [renderRecording(600), render({ ...base, status: 'READY', videoUrl: '/v' })]) {
      expect(html).not.toContain('type="range"')
      expect(html).toContain('클립은 최소 5초, 최대 60초까지 만들 수 있습니다.')
    }
    const html = renderRecording(600)
    expect(html).toContain('value="10:00"')
    expect(html).toContain('value="10:30"')
  })
})

describe('ClipDialog 모바일 스크롤(CLIP-4#2)', () => {
  it('본문은 세로 스크롤 영역이고 취소 · 만들기 줄은 그 밖에 있다', () => {
    for (const html of [renderRecording(0), render({ ...base, status: 'READY', videoUrl: '/v' })]) {
      const scroll = html.indexOf('overflow-y-auto')
      const footer = html.indexOf('shrink-0')
      expect(scroll).toBeGreaterThan(-1)
      expect(footer).toBeGreaterThan(scroll)
      // 스크롤 영역이 닫힌 뒤에 만들기 버튼이 온다
      expect(html.indexOf('만들기</', footer)).toBeGreaterThan(footer)
    }
  })
})
