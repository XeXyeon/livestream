import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'

export const SITE_NAME = '그리드라이브'
/** 이름 없는 화면(홈 등)과 index.html의 기본 제목 */
export const DEFAULT_TITLE = `${SITE_NAME} - 브라우저로 켜고 보는 실시간 방송`

/** 탭 제목: "<페이지 이름> · 그리드라이브". 이름이 없거나(데이터 대기 중) 비면 기본 제목 */
export function pageTitle(name?: string | null): string {
  const trimmed = name?.trim()
  return trimmed ? `${trimmed} · ${SITE_NAME}` : DEFAULT_TITLE
}

/** canonical 주소: 현재 경로(쿼리 · 해시 제외), origin 기준 */
export function canonicalUrl(origin: string, pathname: string): string {
  return `${origin}${pathname}`
}

type PageMeta = {
  /** 페이지 이름. 상세 화면은 방송 · 영상 · 채널 · 클립 · 게시글 제목. 불러오는 중이면 null */
  title?: string | null
  /** 검색에 넣지 않을 화면(스튜디오 · 내 정보 · 위젯 · 로그인 · 가입 · 404) */
  noindex?: boolean
}

/**
 * 탭 제목 · canonical · robots noindex를 화면마다 맞춘다. 새 패키지(react-helmet 등) 없이 head를 직접 고친다.
 * 화면을 떠나면 제목은 기본으로, noindex는 지운다(다음 화면이 자기 값을 다시 넣는다).
 */
export function usePageMeta({ title = null, noindex = false }: PageMeta = {}) {
  const { pathname } = useLocation()

  useEffect(() => {
    document.title = pageTitle(title)
    return () => { document.title = DEFAULT_TITLE }
  }, [title])

  useEffect(() => {
    if (!noindex) return
    const meta = document.createElement('meta')
    meta.name = 'robots'
    meta.content = 'noindex'
    document.head.appendChild(meta)
    return () => meta.remove()
  }, [noindex])

  useEffect(() => {
    let link = document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]')
    if (!link) {
      link = document.createElement('link')
      link.rel = 'canonical'
      document.head.appendChild(link)
    }
    link.href = canonicalUrl(window.location.origin, pathname)
  }, [pathname])
}
