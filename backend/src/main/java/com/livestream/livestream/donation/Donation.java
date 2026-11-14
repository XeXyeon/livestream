package com.livestream.livestream.donation;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.common.entity.BaseEntity;
import com.livestream.livestream.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 후원 한 건 (DESIGN §4.3 donation · §4.13). 생성 때 PENDING, Toss 승인 성공이면 APPROVED, 거절 · 금액 불일치면 FAILED.
 * 방송이 삭제돼도 남는다(받은 후원 합계).
 */
@Getter
@Entity
@Table(name = "donation",
		uniqueConstraints = {
				@UniqueConstraint(name = "uk_donation_order", columnNames = "order_id"),
				@UniqueConstraint(name = "uk_donation_payment_key", columnNames = "payment_key")},
		indexes = {
				@Index(name = "idx_donation_broadcast_status", columnList = "broadcast_id, status"),
				@Index(name = "idx_donation_channel_status", columnList = "channel_id, status")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Donation extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "broadcast_id", nullable = false)
	private Broadcast broadcast;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "channel_id", nullable = false)
	private Channel channel;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "donor_member_id", nullable = false)
	private Member donor;

	@Column(name = "amount", nullable = false)
	private int amount;

	@Column(name = "message", length = 100)
	private String message;

	@Column(name = "order_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
	private String orderId;

	@Column(name = "payment_key", length = 200)
	private String paymentKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private DonationStatus status;

	@Column(name = "approved_at", columnDefinition = "DATETIME(6)")
	private Instant approvedAt;

	public static Donation pending(Broadcast broadcast, Member donor, int amount, String message, String orderId) {
		Donation d = new Donation();
		d.broadcast = broadcast;
		d.channel = broadcast.getChannel();
		d.donor = donor;
		d.amount = amount;
		d.message = message;
		d.orderId = orderId;
		d.status = DonationStatus.PENDING;
		return d;
	}

	public void approve(String paymentKey, Instant at) {
		this.status = DonationStatus.APPROVED;
		this.paymentKey = paymentKey;
		this.approvedAt = at;
	}

	public void fail() {
		this.status = DonationStatus.FAILED;
	}

	public boolean isDonatedBy(Long memberId) {
		return donor.getId().equals(memberId);
	}
}
