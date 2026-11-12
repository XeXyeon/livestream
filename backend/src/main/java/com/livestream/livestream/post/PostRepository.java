package com.livestream.livestream.post;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<Post, Long> {

	Page<Post> findByChannelId(Long channelId, Pageable pageable);

	long countByChannelId(Long channelId);

	/** 상세 · 수정 · 삭제: 소유자 검증과 응답에 쓰는 채널 · 회원을 함께 읽는다. */
	@EntityGraph(attributePaths = {"channel", "channel.member"})
	@Query("select p from Post p where p.id = :id")
	Optional<Post> findWithChannel(Long id);
}
