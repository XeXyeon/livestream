package com.livestream.livestream.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.chat.BroadcastEventPublisher;
import com.livestream.livestream.donation.DonationTestSupport.Order;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * DESIGN §4.13 5: 같은 {paymentKey, orderId, amount} 로 두 스레드가 동시에 confirm 해도(Toss mock 은 느리게 1회 성공)
 * APPROVED 1건 · 합계 1회 · Toss 호출 1회 · 이벤트 1회. 두 요청 모두 200 같은 응답(멱등).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DonationConfirmIdempotencyTest {

	@Autowired MockMvc mvc;
	@Autowired BroadcastLifecycleService lifecycle;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired DonationRepository donationRepository;
	@MockitoBean TossPaymentsClient toss;
	@MockitoBean BroadcastEventPublisher eventPublisher;
	@MockitoBean MediaMtxClient mediaMtxClient;

	@Test
	void 동시_confirm_두_번은_한_번만_승인한다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		lifecycle.onReady(streamer.channelId(), BroadcastSource.BROWSER_PC);
		long broadcastId = broadcastRepository.findOngoing(streamer.channelId()).orElseThrow().getId();
		Order order = DonationTestSupport.create(mvc, donor, broadcastId, 5000, "응원합니다");

		AtomicInteger tossCalls = new AtomicInteger();
		doAnswer(inv -> {
			tossCalls.incrementAndGet();
			Thread.sleep(500); // 두 번째 요청이 잠금에서 기다리도록 승인 호출을 늘린다
			return TossPaymentsClient.Result.approved();
		}).when(toss).confirm(anyString(), anyString(), anyLong());

		CountDownLatch start = new CountDownLatch(1);
		Callable<Integer> confirm = () -> {
			start.await();
			return DonationTestSupport.confirm(mvc, donor, "pay-key-1", order.orderId(), 5000)
					.andReturn().getResponse().getStatus();
		};
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> results = List.of(pool.submit(confirm), pool.submit(confirm));
			start.countDown();
			for (Future<Integer> r : results) {
				assertThat(r.get(20, TimeUnit.SECONDS)).isEqualTo(200);
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(tossCalls.get()).isEqualTo(1);
		verify(toss, times(1)).confirm("pay-key-1", order.orderId(), 5000);
		Donation d = donationRepository.findById(order.donationId()).orElseThrow();
		assertThat(d.getStatus()).isEqualTo(DonationStatus.APPROVED);
		assertThat(d.getPaymentKey()).isEqualTo("pay-key-1");
		assertThat(donationRepository.sumApprovedByBroadcast(broadcastId)).isEqualTo(5000);
		verify(eventPublisher, times(1)).donation(ArgumentMatchers.eq(broadcastId), ArgumentMatchers.any());
	}
}
