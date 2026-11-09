package com.livestream.livestream.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	/** 같은 토큰으로 동시에 들어온 갱신을 직렬화한다(회전 · 유예 판단이 한 번씩 순서대로 일어나게). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t where t.tokenHash = :hash")
	Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

	/**
	 * 회원의 refresh 토큰을 전부 revoke한다(로그아웃 · 재사용 의심). 이미 회전된 토큰도 replaced_by_id를 비워
	 * 회전 유예로 되살아나지 못하게 한다.
	 * <p>벌크 update라 영속성 컨텍스트를 비운다(clearAutomatically). 호출한 뒤에는 이미 읽은 엔티티를 더 쓰지 말 것.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update RefreshToken t
			   set t.revokedAt = coalesce(t.revokedAt, :now), t.replacedById = null, t.updatedAt = :now
			 where t.member.id = :memberId and (t.revokedAt is null or t.replacedById is not null)
			""")
	int revokeAllByMemberId(@Param("memberId") Long memberId, @Param("now") Instant now);
}
