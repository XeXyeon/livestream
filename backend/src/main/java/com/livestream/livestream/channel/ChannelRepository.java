package com.livestream.livestream.channel;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

	Optional<Channel> findByMemberId(Long memberId);

	/**
	 * 수정 · 재발급용. 행을 잠가 같은 채널의 PATCH · 재발급이 직렬로 돈다(전체 컬럼 UPDATE가 옛 키를 되살리는 갱신 유실 방지).
	 * member_id 는 unique 라 gap lock 없이 행 잠금만 잡는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Channel c where c.member.id = :memberId")
	Optional<Channel> findByMemberIdForUpdate(Long memberId);
}
