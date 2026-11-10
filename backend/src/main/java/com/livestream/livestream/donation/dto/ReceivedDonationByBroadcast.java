package com.livestream.livestream.donation.dto;

import java.time.Instant;

/** 방송 한 회에 받은 후원 합계. */
public record ReceivedDonationByBroadcast(Long broadcastId, String title, Instant startedAt, long totalAmount, long count) {
}
