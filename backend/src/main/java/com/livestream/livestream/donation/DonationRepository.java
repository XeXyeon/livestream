package com.livestream.livestream.donation;

import com.livestream.livestream.donation.dto.ReceivedDonationByBroadcast;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DonationRepository extends JpaRepository<Donation, Long> {

	/**
	 * confirm 의 첫 읽기(§4.13): 같은 주문의 동시 승인 요청을 줄 세운다. 트랜잭션의 첫 읽기여야 한다 — 잠금 읽기는 최신 커밋을 보지만
	 * 이미 읽어 둔 엔티티는 갱신하지 않는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from Donation d where d.orderId = :orderId")
	Optional<Donation> findByOrderIdForUpdate(String orderId);

	/** 방송의 승인된 후원, 최신순. */
	@Query(value = """
			select d from Donation d join fetch d.donor
			where d.broadcast.id = :broadcastId and d.status = com.livestream.livestream.donation.DonationStatus.APPROVED
			order by d.approvedAt desc, d.id desc
			""", countQuery = """
			select count(d) from Donation d
			where d.broadcast.id = :broadcastId and d.status = com.livestream.livestream.donation.DonationStatus.APPROVED
			""")
	Page<Donation> findApprovedByBroadcast(Long broadcastId, Pageable pageable);

	@Query("""
			select coalesce(sum(d.amount), 0) from Donation d
			where d.broadcast.id = :broadcastId and d.status = com.livestream.livestream.donation.DonationStatus.APPROVED
			""")
	long sumApprovedByBroadcast(Long broadcastId);

	/** 채널이 받은 승인 후원을 방송별로(삭제된 방송 포함 — 후원 내역은 남는다), 방송 시작 최신순. */
	@Query("""
			select new com.livestream.livestream.donation.dto.ReceivedDonationByBroadcast(
			    b.id, b.title, b.startedAt, sum(d.amount), count(d))
			from Donation d join d.broadcast b
			where d.channel.id = :channelId and d.status = com.livestream.livestream.donation.DonationStatus.APPROVED
			group by b.id, b.title, b.startedAt
			order by b.startedAt desc, b.id desc
			""")
	List<ReceivedDonationByBroadcast> sumApprovedByBroadcastOfChannel(Long channelId);

	// ---------- WID-1 위젯 재전송(Last-Event-ID) ----------

	@Query("""
			select d from Donation d
			where d.id = :id and d.channel.id = :channelId and d.status = com.livestream.livestream.donation.DonationStatus.APPROVED
			""")
	Optional<Donation> findApprovedOfChannel(Long id, Long channelId);

	/** 기준 후원 뒤에 승인된 후원(승인 시각 → id 순). 먼저 만든 주문이 나중에 승인될 수 있어 id 순이 아니라 승인 순으로 본다. */
	@Query("""
			select d from Donation d join fetch d.donor
			where d.channel.id = :channelId and d.status = com.livestream.livestream.donation.DonationStatus.APPROVED
			  and (d.approvedAt > :after or (d.approvedAt = :after and d.id > :afterId))
			order by d.approvedAt asc, d.id asc
			""")
	List<Donation> findApprovedAfter(Long channelId, Instant after, Long afterId, Pageable limit);

	/** 결제 대기 주문 상한(DON-1 리뷰): 승인 호출이 잠금 · 커넥션을 쥐는 시간이 있어 회원별로 미결 주문 수를 묶는다. */
	@Query("""
			select count(d) from Donation d
			where d.donor.id = :memberId and d.status = com.livestream.livestream.donation.DonationStatus.PENDING
			  and d.createdAt > :since
			""")
	long countRecentPending(Long memberId, Instant since);
}
