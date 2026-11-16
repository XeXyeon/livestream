import { SideNav, SideNavCollapseButton } from '@astryxdesign/core/SideNav'
import ChannelSidebarList from './ChannelSidebarList'

type Props = {
  /** sm 이상: 펼침 · 접힘을 오가며 본문을 민다. 그보다 좁으면(보이지 않음) 펼치기는 서랍으로 연다 */
  wide: boolean
  collapsed: boolean
  onCollapsedChange: (collapsed: boolean) => void
  /** 좁은 폭에서 펼치기를 눌렀을 때(서랍 열기) */
  onExpand: () => void
}

/**
 * 왼쪽 채널 사이드바. sm 이상에서는 사라지지 않고 접힌 레일(아바타 줄)로라도 남고, 펼치면 그 자리에서 본문을 민다.
 * 헤더 아래에 붙어(sticky) 본문이 스크롤돼도 남는다. 헤더 · 본문과 같은 바탕에 경계선 없이 붙인다(선이 겹쳐 보이지 않게).
 */
export default function ChannelSidebar({ wide, collapsed, onCollapsedChange, onExpand }: Props) {
  const isCollapsed = wide ? collapsed : true
  const change = (next: boolean) => {
    if (wide) onCollapsedChange(next)
    else if (!next) onExpand()
  }

  return (
    <div className="channel-sidebar sticky top-14 hidden h-[calc(100dvh-3.5rem)] shrink-0 bg-body sm:block" data-collapsed={isCollapsed}>
      {/* buttonLabel은 SideNav가 읽지 않아 접기 버튼을 직접 두고 한국어 이름을 준다. 랜드마크 이름도 기본 영어 대신 지정 */}
      <SideNav
        aria-label="채널"
        collapsible={{ isCollapsed, onCollapsedChange: change, hasButton: false }}
        footerIcons={<SideNavCollapseButton label={isCollapsed ? '사이드바 펼치기' : '사이드바 접기'} />}
      >
        <ChannelSidebarList collapsed={isCollapsed} />
      </SideNav>
    </div>
  )
}
