package com.livestream.livestream.donation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.support.TestAccounts.Account;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 후원 테스트 공용: 주문 생성 · confirm 요청. */
final class DonationTestSupport {

	record Order(long donationId, String orderId, int amount) {
	}

	private DonationTestSupport() {
	}

	static Order create(MockMvc mvc, Account donor, long broadcastId, int amount, String message) throws Exception {
		String body = message == null ? "{\"amount\":" + amount + "}"
				: "{\"amount\":" + amount + ",\"message\":\"" + message + "\"}";
		String res = mvc.perform(post("/api/broadcasts/" + broadcastId + "/donations")
						.header(HttpHeaders.AUTHORIZATION, donor.bearer()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return new Order(((Number) JsonPath.read(res, "$.donationId")).longValue(), JsonPath.read(res, "$.orderId"), amount);
	}

	static ResultActions confirm(MockMvc mvc, Account donor, String paymentKey, String orderId, long amount) throws Exception {
		return mvc.perform(post("/api/donations/confirm").header(HttpHeaders.AUTHORIZATION, donor.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"paymentKey\":\"" + paymentKey + "\",\"orderId\":\"" + orderId + "\",\"amount\":" + amount + "}"));
	}
}
