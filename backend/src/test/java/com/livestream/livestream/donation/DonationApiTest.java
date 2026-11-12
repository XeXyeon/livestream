package com.livestream.livestream.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.chat.BroadcastEventPublisher;
import com.livestream.livestream.chat.dto.DonationMessage;
import com.livestream.livestream.donation.DonationTestSupport.Order;
import com.livestream.livestream.donation.TossPaymentsClient.Outcome;
import com.livestream.livestream.donation.TossPaymentsClient.Result;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** DON-1 (DESIGN §4.13 · §9 DON-1): 생성 → confirm(mock) → APPROVED · 이벤트 · 목록 · 합계, 실패 경로, 비라이브 409. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DonationApiTest {

	@Autowired MockMvc mvc;
	@Autowired BroadcastLifecycleService lifecycle;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired DonationRepository donationRepository;
	@MockitoBean TossPaymentsClient toss;
	@MockitoBean BroadcastEventPublisher eventPublisher;
	@MockitoBean MediaMtxClient mediaMtxClient;

	@Test
	void 생성_승인_뒤_이벤트_목록_합계에_반영된다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);

		mvc.perform(post("/api/broadcasts/" + broadcastId + "/donations").header(HttpHeaders.AUTHORIZATION, donor.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":3000,\"message\":\"  화이팅  \"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.orderId").isString())
				.andExpect(jsonPath("$.amount").value(3000))
				.andExpect(jsonPath("$.orderName").value("후원"))
				.andExpect(jsonPath("$.customerName").value(donor.nickname()))
				.andExpect(jsonPath("$.clientKey").exists());
		Donation pending = donationRepository.findAll().stream()
				.filter(d -> d.getBroadcast().getId() == broadcastId).findFirst().orElseThrow();
		assertThat(pending.getStatus()).isEqualTo(DonationStatus.PENDING);
		assertThat(pending.getMessage()).isEqualTo("화이팅");

		when(toss.confirm(anyString(), anyString(), anyLong())).thenReturn(Result.approved());
		DonationTestSupport.confirm(mvc, donor, "pk-a", pending.getOrderId(), 3000)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(pending.getId()))
				.andExpect(jsonPath("$.donor.nickname").value(donor.nickname()))
				.andExpect(jsonPath("$.amount").value(3000))
				.andExpect(jsonPath("$.message").value("화이팅"))
				.andExpect(jsonPath("$.approvedAt").isString());
		ArgumentCaptor<DonationMessage> event = ArgumentCaptor.forClass(DonationMessage.class);
		verify(eventPublisher).donation(eq(broadcastId), event.capture());
		assertThat(event.getValue().type()).isEqualTo("donation");
		assertThat(event.getValue().donor().nickname()).isEqualTo(donor.nickname());
		assertThat(event.getValue().amount()).isEqualTo(3000);

		// 같은 요청 다시 → 같은 응답, Toss · 이벤트는 다시 안 감
		DonationTestSupport.confirm(mvc, donor, "pk-a", pending.getOrderId(), 3000)
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(pending.getId()));
		verify(toss, times(1)).confirm(anyString(), anyString(), anyLong());
		verify(eventPublisher, times(1)).donation(eq(broadcastId), org.mockito.ArgumentMatchers.any());
		// 다른 paymentKey 로 같은 주문 → 409
		DonationTestSupport.confirm(mvc, donor, "pk-other", pending.getOrderId(), 3000)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DONATION_ALREADY_PROCESSED"));

		Order second = DonationTestSupport.create(mvc, donor, broadcastId, 1000, null);
		DonationTestSupport.confirm(mvc, donor, "pk-b", second.orderId(), 1000).andExpect(status().isOk());
		Order unpaid = DonationTestSupport.create(mvc, donor, broadcastId, 50000, null); // PENDING 은 합계에서 빠진다

		mvc.perform(get("/api/broadcasts/" + broadcastId + "/donations"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].amount").value(1000))
				.andExpect(jsonPath("$.items[0].message").doesNotExist())
				.andExpect(jsonPath("$.items[1].message").value("화이팅"))
				.andExpect(jsonPath("$.totalAmount").value(4000))
				.andExpect(jsonPath("$.totalCount").value(2));
		mvc.perform(get("/api/broadcasts/" + broadcastId)).andExpect(jsonPath("$.donationTotal").value(4000));
		mvc.perform(get("/api/members/me/donations/received").header(HttpHeaders.AUTHORIZATION, streamer.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalAmount").value(4000))
				.andExpect(jsonPath("$.byBroadcast[0].broadcastId").value(broadcastId))
				.andExpect(jsonPath("$.byBroadcast[0].totalAmount").value(4000))
				.andExpect(jsonPath("$.byBroadcast[0].count").value(2));
		mvc.perform(get("/api/members/me/donations/received").header(HttpHeaders.AUTHORIZATION, donor.bearer()))
				.andExpect(jsonPath("$.totalAmount").value(0));
		assertThat(donationRepository.findById(unpaid.donationId()).orElseThrow().getStatus()).isEqualTo(DonationStatus.PENDING);
	}

	@Test
	void 금액_불일치는_400_이고_그_주문은_실패로_닫힌다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		Order order = DonationTestSupport.create(mvc, donor, start(streamer), 5000, null);

		DonationTestSupport.confirm(mvc, donor, "pk-x", order.orderId(), 100)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DONATION_AMOUNT_MISMATCH"));
		assertThat(donationRepository.findById(order.donationId()).orElseThrow().getStatus()).isEqualTo(DonationStatus.FAILED);
		DonationTestSupport.confirm(mvc, donor, "pk-x", order.orderId(), 5000)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DONATION_ALREADY_PROCESSED"));
		verify(toss, never()).confirm(anyString(), anyString(), anyLong());
	}

	@Test
	void Toss_거절은_실패로_결과를_모르면_대기로_두고_다시_받는다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);

		Order rejected = DonationTestSupport.create(mvc, donor, broadcastId, 2000, null);
		when(toss.confirm(anyString(), eq(rejected.orderId()), anyLong()))
				.thenReturn(new Result(Outcome.REJECTED, "REJECT_CARD_COMPANY", "카드사에서 거절했습니다"));
		DonationTestSupport.confirm(mvc, donor, "pk-r", rejected.orderId(), 2000)
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("PAYMENT_CONFIRM_FAILED"))
				.andExpect(jsonPath("$.message").value("결제 승인에 실패했습니다: 카드사에서 거절했습니다"));
		assertThat(donationRepository.findById(rejected.donationId()).orElseThrow().getStatus()).isEqualTo(DonationStatus.FAILED);

		Order unknown = DonationTestSupport.create(mvc, donor, broadcastId, 2000, null);
		when(toss.confirm(anyString(), eq(unknown.orderId()), anyLong()))
				.thenReturn(new Result(Outcome.UNKNOWN, null, null))
				.thenReturn(Result.approved());
		DonationTestSupport.confirm(mvc, donor, "pk-u", unknown.orderId(), 2000).andExpect(status().isBadGateway());
		assertThat(donationRepository.findById(unknown.donationId()).orElseThrow().getStatus()).isEqualTo(DonationStatus.PENDING);
		DonationTestSupport.confirm(mvc, donor, "pk-u", unknown.orderId(), 2000).andExpect(status().isOk());
		assertThat(donationRepository.findById(unknown.donationId()).orElseThrow().getStatus()).isEqualTo(DonationStatus.APPROVED);
		mvc.perform(get("/api/broadcasts/" + broadcastId + "/donations")).andExpect(jsonPath("$.totalAmount").value(2000));
	}

	@Test
	void 남의_주문도_없는_주문도_404() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		Order order = DonationTestSupport.create(mvc, donor, start(streamer), 1000, null);

		DonationTestSupport.confirm(mvc, other, "pk", order.orderId(), 1000)
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DONATION_NOT_FOUND"));
		DonationTestSupport.confirm(mvc, donor, "pk", "00000000-0000-0000-0000-000000000000", 1000)
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DONATION_NOT_FOUND"));
		mvc.perform(post("/api/donations/confirm").contentType(MediaType.APPLICATION_JSON)
				.content("{\"paymentKey\":\"pk\",\"orderId\":\"" + order.orderId() + "\",\"amount\":1000}"))
				.andExpect(status().isUnauthorized());
		verify(toss, never()).confirm(anyString(), anyString(), anyLong());
		assertThat(donationRepository.findById(order.donationId()).orElseThrow().getStatus()).isEqualTo(DonationStatus.PENDING);
	}

	@Test
	void 생성_검증_금액_메시지_비라이브_비로그인() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);

		for (String body : new String[] {"{\"amount\":999}", "{\"amount\":100001}", "{}",
				"{\"amount\":1000,\"message\":\"" + "가".repeat(101) + "\"}", "{\"amount\":1000,\"message\":\"a\\u202Eb\"}"}) {
			mvc.perform(post("/api/broadcasts/" + broadcastId + "/donations").header(HttpHeaders.AUTHORIZATION, donor.bearer())
							.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest());
		}
		DonationTestSupport.create(mvc, donor, broadcastId, 1000, "가".repeat(100));
		DonationTestSupport.create(mvc, donor, broadcastId, 100000, "   ");
		mvc.perform(post("/api/broadcasts/" + broadcastId + "/donations")
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
				.andExpect(status().isUnauthorized());

		lifecycle.endOngoingOf(streamer.channelId());
		mvc.perform(post("/api/broadcasts/" + broadcastId + "/donations").header(HttpHeaders.AUTHORIZATION, donor.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BROADCAST_NOT_LIVE"));
		mvc.perform(post("/api/broadcasts/999999999/donations").header(HttpHeaders.AUTHORIZATION, donor.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/broadcasts/999999999/donations")).andExpect(status().isNotFound());
	}

	@Test
	void 결제_대기_주문은_회원별_상한이_있다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);
		for (int i = 0; i < DonationService.PENDING_LIMIT; i++) {
			DonationTestSupport.create(mvc, donor, broadcastId, 1000, null);
		}
		mvc.perform(post("/api/broadcasts/" + broadcastId + "/donations").header(HttpHeaders.AUTHORIZATION, donor.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		// 다른 회원은 영향 없음
		DonationTestSupport.create(mvc, TestAccounts.create(mvc), broadcastId, 1000, null);
	}

	@Test
	void 방송_종료_뒤_승인되면_채팅_이벤트는_보내지_않는다() throws Exception {
		Account streamer = TestAccounts.create(mvc);
		Account donor = TestAccounts.create(mvc);
		long broadcastId = start(streamer);
		Order order = DonationTestSupport.create(mvc, donor, broadcastId, 2000, null);
		lifecycle.endOngoingOf(streamer.channelId());

		when(toss.confirm(anyString(), anyString(), anyLong())).thenReturn(Result.approved());
		DonationTestSupport.confirm(mvc, donor, "pk-late", order.orderId(), 2000).andExpect(status().isOk());
		assertThat(donationRepository.findById(order.donationId()).orElseThrow().getStatus()).isEqualTo(DonationStatus.APPROVED);
		verify(eventPublisher, never()).donation(eq(broadcastId), org.mockito.ArgumentMatchers.any());
		mvc.perform(get("/api/broadcasts/" + broadcastId + "/donations")).andExpect(jsonPath("$.totalAmount").value(2000));
	}

	private long start(Account streamer) {
		lifecycle.onReady(streamer.channelId(), BroadcastSource.BROWSER_PC);
		return broadcastRepository.findOngoing(streamer.channelId()).orElseThrow().getId();
	}
}
