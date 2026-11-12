import { useState } from 'react'
import { SideNav, SideNavCollapseButton } from '@astryxdesign/core/SideNav'
import ChannelSidebarList from './ChannelSidebarList'
import { readCollapsed, writeCollapsed } from './sidebarChannels'

/**
 * PC(xl 이상) 왼쪽 채널 사이드바. 접기 · 펴기는 ASTRYX SideNav 접기 버튼을 쓰고 상태를 localStorage에 기억한다.
 * 헤더 아래에 붙어(sticky) 본문이 스크롤돼도 남는다.
 * xl 기준: 본문 그리드(Live 영상+채팅, 홈 4열)가 뷰포트 lg 기준이라 lg에서 사이드바를 고정하면 본문이 너무 좁아진다.
 */
export default function ChannelSidebar() {
  const [collapsed, setCollapsed] = useState(() => readCollapsed())

  function change(next: boolean) {
    setCollapsed(next)
    writeCollapsed(next)
  }

  return (
    <div className="sticky top-14 hidden h-[calc(100dvh-3.5rem)] shrink-0 border-r border-border bg-surface xl:block">
      {/* buttonLabel은 SideNav가 읽지 않아 접기 버튼을 직접 두고 한국어 이름을 준다. 랜드마크 이름도 기본 영어 대신 지정 */}
      <SideNav
        aria-label="채널"
        collapsible={{ isCollapsed: collapsed, onCollapsedChange: change, hasButton: false }}
        footerIcons={<SideNavCollapseButton label={collapsed ? '사이드바 펼치기' : '사이드바 접기'} />}
      >
        <ChannelSidebarList collapsed={collapsed} />
      </SideNav>
    </div>
  )
}
