package com.livestream.livestream.channel;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

	Optional<Channel> findByMemberId(Long memberId);

	/** 알림 위젯 SSE 연결(WID-1). widget_key 는 유니크. */
	Optional<Channel> findByWidgetKey(String widgetKey);

	/**
	 * 수정 · 재발급용. 행을 잠가 같은 채널의 PATCH · 재발급이 직렬로 돈다(전체 컬럼 UPDATE가 옛 키를 되살리는 갱신 유실 방지).
	 * member_id 는 unique 라 gap lock 없이 행 잠금만 잡는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Channel c where c.member.id = :memberId")
	Optional<Channel> findByMemberIdForUpdate(Long memberId);

	/**
	 * 방송 상태 전이(BC-1)용. 같은 채널의 ready · not-ready 훅 · 종료 · 유예 만료가 직렬로 돌게 채널 행을 잠근다
	 * (훅 두 개가 동시에 와서 방송이 2건 생기는 것 방지). 잠금 순서는 항상 channel → broadcast.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Channel c where c.id = :id")
	Optional<Channel> findByIdForUpdate(Long id);

	/**
	 * 통합 검색(SEARCH-1): 채널 이름 또는 주인 닉네임이 패턴에 맞는 채널, 이름 오름차순. pattern 은 SearchQuery 가 이스케이프한
	 * {@code %q%} 값이다. 정렬은 쿼리에 있으니 pageable 은 정렬 없이 넘긴다.
	 */
	@Query(value = """
			select c from Channel c join fetch c.member m
			where c.name like :pattern escape '\\' or m.nickname like :pattern escape '\\'
			order by c.name, c.id
			""", countQuery = """
			select count(c) from Channel c join c.member m
			where c.name like :pattern escape '\\' or m.nickname like :pattern escape '\\'
			""")
	Page<Channel> search(String pattern, Pageable pageable);
}
