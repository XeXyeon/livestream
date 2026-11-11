package com.livestream.livestream.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** AUTH-1#2: 만료된 지 오래된 refresh_token 행만 지운다. 만료 전(회전 · revoke 포함) 행은 재사용 의심 판단용으로 남긴다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenCleanupSchedulerTest {

	@Autowired MockMvc mvc;
	@Autowired EntityManager em;
	@Autowired TransactionTemplate tx;
	@Autowired RefreshTokenCleanupScheduler scheduler;

	@Test
	void 만료된_지_보관기간이_지난_행만_삭제한다() throws Exception {
		String old = login();
		String justExpired = login();
		String revokedButValid = login();
		String valid = login();
		setExpiry(old, Instant.now().minus(RefreshTokenCleanupScheduler.RETENTION).minusSeconds(60));
		setExpiry(justExpired, Instant.now().minusSeconds(60));
		tx.executeWithoutResult(s -> em.createQuery("update RefreshToken t set t.revokedAt = :ts where t.tokenHash = :h")
				.setParameter("ts", Instant.now()).setParameter("h", AuthService.hash(revokedButValid)).executeUpdate());

		int deleted = scheduler.purgeExpired(Instant.now());

		assertThat(deleted).isGreaterThanOrEqualTo(1);
		assertThat(exists(old)).isFalse();
		assertThat(exists(justExpired)).isTrue();
		assertThat(exists(revokedButValid)).isTrue();
		assertThat(exists(valid)).isTrue();
	}

	@Test
	void 지울_행이_없으면_0() {
		assertThat(scheduler.purgeExpired(Instant.now().minus(Duration.ofDays(3650)))).isZero();
	}

	private String login() throws Exception {
		String id = UUID.randomUUID().toString().replace("-", "");
		String email = "c" + id.substring(0, 12) + "@example.com";
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"password123\",\"nickname\":\"c" + id.substring(12, 24) + "\"}"))
				.andExpect(status().isCreated());
		var result = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"password123\"}")).andExpect(status().isOk()).andReturn();
		return result.getResponse().getCookie("refresh_token").getValue();
	}

	private void setExpiry(String raw, Instant at) {
		tx.executeWithoutResult(s -> em.createQuery("update RefreshToken t set t.expiresAt = :ts where t.tokenHash = :h")
				.setParameter("ts", at).setParameter("h", AuthService.hash(raw)).executeUpdate());
	}

	private boolean exists(String raw) {
		Long count = tx.execute(s -> em.createQuery("select count(t) from RefreshToken t where t.tokenHash = :h", Long.class)
				.setParameter("h", AuthService.hash(raw)).getSingleResult());
		return count != null && count > 0;
	}
}
