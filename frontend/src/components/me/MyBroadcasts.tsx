import { useInfiniteQuery } from '@tanstack/react-query'
import { Banner } from '@astryxdesign/core/Banner'
import { Button } from '@astryxdesign/core/Button'
import { Heading } from '@astryxdesign/core/Heading'
import { listMyBroadcasts } from '../../api/broadcasts'
import Spinner from '../common/Spinner'
import MyBroadcastRow from './MyBroadcastRow'

// §6.3 S8 내 방송 기록(GET /members/me/broadcasts): 제목 수정 · 삭제 · 다시보기 공개 설정
export default function MyBroadcasts() {
  const query = useInfiniteQuery({
    queryKey: ['me', 'broadcasts'],
    queryFn: ({ pageParam }) => listMyBroadcasts(pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasNext ? last.page + 1 : undefined),
  })
  const items = query.data?.pages.flatMap((page) => page.items) ?? []

  return (
    <section aria-labelledby="my-broadcasts" className="rounded-lg border border-border bg-surface p-5">
      <Heading level={2} id="my-broadcasts">내 방송 기록</Heading>
      {query.isPending && <Spinner label="방송 기록을 불러오는 중" />}
      {query.isError && <div className="mt-3"><Banner role="alert" status="error" title="방송 기록을 불러오지 못했습니다." /></div>}
      {query.isSuccess && items.length === 0 && <p className="mt-4 text-sm text-secondary">아직 방송한 기록이 없습니다.</p>}
      {items.length > 0 && (
        <ul className="mt-2 divide-y divide-border">
          {items.map((item) => <MyBroadcastRow key={item.id} item={item} />)}
        </ul>
      )}
      {query.hasNextPage && (
        <div className="mt-4 flex justify-center">
          <Button label="더 보기" variant="secondary" isLoading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
        </div>
      )}
    </section>
  )
}
