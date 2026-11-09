package com.livestream.livestream.channel;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

	Optional<Channel> findByMemberId(Long memberId);
}
