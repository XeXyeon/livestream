package com.livestream.livestream.post;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<Post, Long> {

	Page<Post> findByChannelId(Long channelId, Pageable pageable);

	long countByChannelId(Long channelId);

	/** 댓글 작성: 공유 잠금(FOR SHARE) — 게시글 삭제와 직렬화한다. */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select p from Post p where p.id = :id")
	Optional<Post> findByIdForShare(Long id);

	/** 상세 · 수정 · 삭제: 소유자 검증과 응답에 쓰는 채널 · 회원을 함께 읽는다. */
	@EntityGraph(attributePaths = {"channel", "channel.member"})
	@Query("select p from Post p where p.id = :id")
	Optional<Post> findWithChannel(Long id);
}
