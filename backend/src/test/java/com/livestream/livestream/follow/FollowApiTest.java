package com.livestream.livestream.follow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.broadcast.BroadcastStatus;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** FOLLOW-1 통합 테스트: 팔로우 · 해제 멱등, 자기 채널 400, 401 · 404, 채널 · 방송 상세 필드, 내 팔로우 목록 정렬. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FollowApiTest {

	@Autowired MockMvc mvc;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired ChannelRepository channelRepository;
	@Autowired TransactionTemplate tx;
	@Autowired EntityManager em;

	private ResultActions follow(Account me, long channelId) throws Exception {
		return mvc.perform(post("/api/channels/" + channelId + "/follow").header("Authorization", me.bearer()));
	}

	private ResultActions unfollow(Account me, long channelId) throws Exception {
		return mvc.perform(delete("/api/channels/" + channelId + "/follow").header("Authorization", me.bearer()));
	}

	@Test
	void 팔로우는_멱등이다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account fan = TestAccounts.create(mvc);
		for (int i = 0; i < 2; i++) {
			follow(fan, owner.channelId()).andExpect(status().isOk())
					.andExpect(jsonPath("$.channelId").value(owner.channelId()))
					.andExpect(jsonPath("$.following").value(true))
					.andExpect(jsonPath("$.followerCount").value(1));
		}
	}

	/** 동시 POST는 뒤 요청이 unique 위반을 받지만 "이미 팔로우 중"으로 흡수해 모두 200 · followerCount 1 이어야 한다. */
	@Test
	void 같은_채널을_동시에_팔로우해도_모두_200이고_행은_하나다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account fan = TestAccounts.create(mvc);
		int n = 3;
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				Callable<Integer> task = () -> {
					ready.countDown();
					go.await();
					return follow(fan, owner.channelId()).andReturn().getResponse().getStatus();
				};
				results.add(pool.submit(task));
			}
			ready.await();
			go.countDown();
			for (Future<Integer> f : results) {
				org.assertj.core.api.Assertions.assertThat(f.get()).isEqualTo(200);
			}
		} finally {
			pool.shutdownNow();
		}
		mvc.perform(get("/api/channels/" + owner.channelId()).header("Authorization", fan.bearer()))
				.andExpect(jsonPath("$.followerCount").value(1)).andExpect(jsonPath("$.isFollowing").value(true));
	}

	@Test
	void 해제는_멱등이다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account fan = TestAccounts.create(mvc);
		follow(fan, owner.channelId()).andExpect(status().isOk());
		for (int i = 0; i < 2; i++) {
			unfollow(fan, owner.channelId()).andExpect(status().isOk())
					.andExpect(jsonPath("$.following").value(false))
					.andExpect(jsonPath("$.followerCount").value(0));
		}
	}

	@Test
	void 자기_채널은_팔로우할_수_없다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		follow(owner, owner.channelId()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.message").value("자기 채널은 팔로우할 수 없습니다"));
		unfollow(owner, owner.channelId()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void 비로그인은_401이다() throws Exception {
		mvc.perform(post("/api/channels/1/follow")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/channels/1/follow")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/members/me/follows")).andExpect(status().isUnauthorized());
	}

	@Test
	void 없는_채널은_404이다() throws Exception {
		Account fan = TestAccounts.create(mvc);
		follow(fan, 999_999_999L).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
		unfollow(fan, 999_999_999L).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
	}

	@Test
	void 채널_상세는_팔로워_수와_내_팔로우_여부를_담는다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account fan = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		follow(fan, owner.channelId()).andExpect(status().isOk());
		String url = "/api/channels/" + owner.channelId();

		mvc.perform(get(url).header("Authorization", fan.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.isFollowing").value(true)).andExpect(jsonPath("$.followerCount").value(1));
		mvc.perform(get(url).header("Authorization", other.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.isFollowing").value(false)).andExpect(jsonPath("$.followerCount").value(1));
		mvc.perform(get(url)).andExpect(status().isOk())
				.andExpect(jsonPath("$.isFollowing").value(false)).andExpect(jsonPath("$.followerCount").value(1));
		mvc.perform(get(url).header("Authorization", owner.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.isFollowing").value(false));
	}

	@Test
	void 방송_상세는_팔로워_수와_내_팔로우_여부를_담는다() throws Exception {
		Account owner = TestAccounts.create(mvc);
		Account fan = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long broadcastId = startLive(owner.channelId());
		follow(fan, owner.channelId()).andExpect(status().isOk());
		String url = "/api/broadcasts/" + broadcastId;

		mvc.perform(get(url).header("Authorization", fan.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.isFollowing").value(true)).andExpect(jsonPath("$.followerCount").value(1));
		mvc.perform(get(url).header("Authorization", other.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.isFollowing").value(false)).andExpect(jsonPath("$.followerCount").value(1));
		mvc.perform(get(url)).andExpect(status().isOk())
				.andExpect(jsonPath("$.isFollowing").value(false)).andExpect(jsonPath("$.followerCount").value(1));
	}

	@Test
	void 내_팔로우_목록은_라이브가_먼저_그다음_최근_팔로우_순이다() throws Exception {
		Account fan = TestAccounts.create(mvc);
		Account a = TestAccounts.create(mvc);
		Account b = TestAccounts.create(mvc);
		Account c = TestAccounts.create(mvc);
		follow(fan, a.channelId()).andExpect(status().isOk());
		Thread.sleep(20);
		follow(fan, b.channelId()).andExpect(status().isOk());
		Thread.sleep(20);
		follow(fan, c.channelId()).andExpect(status().isOk());
		long live = startLive(b.channelId());

		mvc.perform(get("/api/members/me/follows").header("Authorization", fan.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].channelId").value(b.channelId()))
				.andExpect(jsonPath("$[0].isLive").value(true))
				.andExpect(jsonPath("$[0].liveBroadcastId").value(live))
				.andExpect(jsonPath("$[0].viewerCount").value(0))
				.andExpect(jsonPath("$[1].channelId").value(c.channelId()))
				.andExpect(jsonPath("$[1].isLive").value(false))
				.andExpect(jsonPath("$[1].liveBroadcastId").doesNotExist())
				.andExpect(jsonPath("$[1].viewerCount").value(0))
				.andExpect(jsonPath("$[2].channelId").value(a.channelId()))
				.andExpect(jsonPath("$[2].isLive").value(false))
				.andExpect(jsonPath("$[2].followedAt").exists());
	}

	/** 라이브 채널끼리는 시청자 수가 같으면(둘 다 0) 방송 시작이 늦은 쪽이 먼저다. DISCONNECTED 도 라이브로 친다. */
	@Test
	void 라이브_채널끼리는_방송_시작_내림차순이고_DISCONNECTED도_라이브다() throws Exception {
		Account fan = TestAccounts.create(mvc);
		Account a = TestAccounts.create(mvc);
		Account b = TestAccounts.create(mvc);
		follow(fan, a.channelId()).andExpect(status().isOk());
		follow(fan, b.channelId()).andExpect(status().isOk());
		Instant now = Instant.now();
		long older = startLive(a.channelId(), now.minusSeconds(600));
		long newer = startLive(b.channelId(), now);
		// disconnect() 는 broadcast 패키지 밖에서 못 부르므로 상태만 직접 바꾼다
		tx.executeWithoutResult(s -> em.createQuery("update Broadcast b set b.status = :st where b.id = :id")
				.setParameter("st", BroadcastStatus.DISCONNECTED).setParameter("id", older).executeUpdate());

		mvc.perform(get("/api/members/me/follows").header("Authorization", fan.bearer())).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].channelId").value(b.channelId()))
				.andExpect(jsonPath("$[0].liveBroadcastId").value(newer))
				.andExpect(jsonPath("$[1].channelId").value(a.channelId()))
				.andExpect(jsonPath("$[1].isLive").value(true))
				.andExpect(jsonPath("$[1].liveBroadcastId").value(older));
	}

	private long startLive(long channelId) {
		return startLive(channelId, Instant.now());
	}

	private long startLive(long channelId, Instant startedAt) {
		Long id = tx.execute(s -> broadcastRepository.save(
				Broadcast.start(channelRepository.getReferenceById(channelId), BroadcastSource.BROWSER_PC, startedAt))
				.getId());
		return id;
	}
}
