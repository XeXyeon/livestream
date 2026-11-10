package com.livestream.livestream.recording;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingRepository extends JpaRepository<Recording, Long> {

	Optional<Recording> findByBroadcastId(Long broadcastId);
}
