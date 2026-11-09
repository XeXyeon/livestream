package com.livestream.livestream.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.common.security.OwnershipGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** CH-1 통합 테스트: GET/PATCH /channels/me, 키 재발급 2개, GET /channels/{id}, 다른 회원 격리 · 403 (DESIGN §4.16). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChannelApiTest {

	private static final String PASSWORD = "password123";

	@Autowired MockMvc mvc;
	@Autowired ChannelRepository channelRepository;
	@Autowired AppProperties props;
	@Autowired TransactionTemplate tx;
	@MockitoBean LiveBroadcastTerminator terminator;

	record Account(String nickname, String access, long channelId) {
	}

	// ---------- GET /channels/me ----------

	@Test
	void 내_채널은_로그인해야_조회된다() throws Exception {
		mvc.perform(get("/api/channels/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(patch("/api/channels/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(post("/api/channels/me/stream-key/rotate")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/channels/me/widget-key/rotate")).andExpect(status().isUnauthorized());
	}

	@Test
	void 내_채널은_기본값_키_송출_안내를_담는다() throws Exception {
		Account a = newAccount();
		String base = props.publicUrl();
		String host = java.net.URI.create(base).getHost();

		String body = me(a).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(a.channelId()))
				.andExpect(jsonPath("$.name").value(a.nickname()))
				.andExpect(jsonPath("$.description").doesNotExist())
				.andExpect(jsonPath("$.title").value(a.nickname() + "의 방송"))
				.andExpect(jsonPath("$.category.code").value("ETC"))
				.andExpect(jsonPath("$.category.label").value("기타"))
				.andExpect(jsonPath("$.autoPublishRecording").value(true))
				.andExpect(jsonPath("$.owner.nickname").value(a.nickname()))
				.andExpect(jsonPath("$.liveBroadcastId").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.currentBroadcast").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.recordingCount").value(0))
				.andExpect(jsonPath("$.postCount").value(0))
				.andReturn().getResponse().getContentAsString();

		String streamKey = JsonPath.read(body, "$.streamKey");
		String widgetKey = JsonPath.read(body, "$.widgetKey");
		assertThat(streamKey).matches("[0-9a-f]{32}");
		assertThat(widgetKey).matches("[0-9a-f]{32}").isNotEqualTo(streamKey);
		long id = a.channelId();
		assertThat((String) JsonPath.read(body, "$.publish.whipUrl")).isEqualTo(base + "/live/" + id + "/whip");
		assertThat((String) JsonPath.read(body, "$.publish.obsWhipUrl")).isEqualTo(base + "/live/" + id + "/whip?src=obs");
		assertThat((String) JsonPath.read(body, "$.publish.obsWhipBearer")).isEqualTo(streamKey);
		assertThat((String) JsonPath.read(body, "$.publish.rtmpUrl")).isEqualTo("rtmp://" + host + "/live");
		assertThat((String) JsonPath.read(body, "$.publish.rtmpStreamKey")).isEqualTo(id + "?user=obs&pass=" + streamKey);
		assertThat((String) JsonPath.read(body, "$.widgetUrl")).isEqualTo(base + "/widget/" + widgetKey);
	}

	// ---------- PATCH /channels/me ----------

	@Test
	void 보낸_필드만_수정되고_응답과_조회에_반영된다() throws Exception {
		Account a = newAccount();
		String before = me(a).andReturn().getResponse().getContentAsString();

		patchMe(a, "{\"name\":\"  새 채널  \",\"description\":\"소개글\",\"title\":\"오늘의 방송\",\"category\":\"GAME\",\"autoPublishRecording\":false}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("새 채널"))
				.andExpect(jsonPath("$.description").value("소개글"))
				.andExpect(jsonPath("$.title").value("오늘의 방송"))
				.andExpect(jsonPath("$.category.code").value("GAME"))
				.andExpect(jsonPath("$.category.label").value("게임"))
				.andExpect(jsonPath("$.autoPublishRecording").value(false))
				.andExpect(jsonPath("$.streamKey").value((String) JsonPath.read(before, "$.streamKey")));

		// 일부만 보내면 나머지는 그대로
		patchMe(a, "{\"title\":\"두 번째 방송\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("두 번째 방송"))
				.andExpect(jsonPath("$.name").value("새 채널"))
				.andExpect(jsonPath("$.category.code").value("GAME"))
				.andExpect(jsonPath("$.autoPublishRecording").value(false));
		// 빈 본문 · null 필드는 변경 없음
		patchMe(a, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.title").value("두 번째 방송"));
		patchMe(a, "{\"name\":null,\"category\":null}").andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("새 채널")).andExpect(jsonPath("$.category.code").value("GAME"));

		me(a).andExpect(jsonPath("$.name").value("새 채널")).andExpect(jsonPath("$.description").value("소개글"))
				.andExpect(jsonPath("$.title").value("두 번째 방송"));
		// 공개 조회에도 name · description · category 반영
		mvc.perform(get("/api/channels/" + a.channelId())).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("새 채널")).andExpect(jsonPath("$.description").value("소개글"))
				.andExpect(jsonPath("$.category.code").value("GAME"));

		// 소개는 빈 문자열로 지운다
		patchMe(a, "{\"description\":\"\"}").andExpect(status().isOk()).andExpect(jsonPath("$.description").doesNotExist());
		patchMe(a, "{\"description\":\"   \"}").andExpect(status().isOk()).andExpect(jsonPath("$.description").doesNotExist());
	}

	@Test
	void 잘못된_수정_값은_400이고_아무것도_바뀌지_않는다() throws Exception {
		Account a = newAccount();
		patchMe(a, "{\"name\":\"\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		patchMe(a, "{\"name\":\"   \"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		patchMe(a, "{\"name\":\"" + "가".repeat(31) + "\"}").andExpect(status().isBadRequest());
		patchMe(a, "{\"title\":\" \"}").andExpect(status().isBadRequest());
		patchMe(a, "{\"title\":\"" + "a".repeat(101) + "\"}").andExpect(status().isBadRequest());
		patchMe(a, "{\"description\":\"" + "a".repeat(501) + "\"}").andExpect(status().isBadRequest());
		patchMe(a, "{\"category\":\"COOKING\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		patchMe(a, "{\"autoPublishRecording\":\"maybe\"}").andExpect(status().isBadRequest());
		// 경계값은 통과
		patchMe(a, "{\"name\":\"" + "가".repeat(30) + "\",\"title\":\"" + "a".repeat(100) + "\",\"description\":\"" + "a".repeat(500) + "\"}")
				.andExpect(status().isOk());

		Account b = newAccount();
		patchMe(b, "{\"name\":\"ok\",\"title\":\"\"}").andExpect(status().isBadRequest());
		me(b).andExpect(jsonPath("$.name").value(b.nickname())); // 부분 적용 없음
	}

	// ---------- 재발급 ----------

	@Test
	void 스트림_키_재발급은_새_키를_주고_옛_키를_버리며_진행_방송을_종료_훅으로_알린다() throws Exception {
		Account a = newAccount();
		String oldKey = JsonPath.read(me(a).andReturn().getResponse().getContentAsString(), "$.streamKey");
		String widgetKey = JsonPath.read(me(a).andReturn().getResponse().getContentAsString(), "$.widgetKey");

		String newKey = JsonPath.read(mvc.perform(post("/api/channels/me/stream-key/rotate").header("Authorization", "Bearer " + a.access()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.streamKey").isString())
				.andReturn().getResponse().getContentAsString(), "$.streamKey");

		assertThat(newKey).matches("[0-9a-f]{32}").isNotEqualTo(oldKey);
		verify(terminator).endLiveBroadcastOf(a.channelId());
		me(a).andExpect(jsonPath("$.streamKey").value(newKey))
				.andExpect(jsonPath("$.publish.obsWhipBearer").value(newKey))
				.andExpect(jsonPath("$.publish.rtmpStreamKey").value(a.channelId() + "?user=obs&pass=" + newKey))
				.andExpect(jsonPath("$.widgetKey").value(widgetKey)); // 위젯 키는 그대로
		tx.executeWithoutResult(s -> assertThat(channelRepository.findById(a.channelId()).orElseThrow().getStreamKey()).isEqualTo(newKey));
	}

	@Test
	void 위젯_키_재발급은_새_키와_URL을_주고_스트림_키는_그대로다() throws Exception {
		Account a = newAccount();
		String body = me(a).andReturn().getResponse().getContentAsString();
		String oldWidget = JsonPath.read(body, "$.widgetKey");
		String streamKey = JsonPath.read(body, "$.streamKey");

		String res = mvc.perform(post("/api/channels/me/widget-key/rotate").header("Authorization", "Bearer " + a.access()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String newWidget = JsonPath.read(res, "$.widgetKey");

		assertThat(newWidget).matches("[0-9a-f]{32}").isNotEqualTo(oldWidget);
		assertThat((String) JsonPath.read(res, "$.widgetUrl")).isEqualTo(props.publicUrl() + "/widget/" + newWidget);
		me(a).andExpect(jsonPath("$.widgetKey").value(newWidget)).andExpect(jsonPath("$.widgetUrl").value(props.publicUrl() + "/widget/" + newWidget))
				.andExpect(jsonPath("$.streamKey").value(streamKey));
		verifyNoInteractions(terminator);
	}

	@Test
	void 이름_제목은_이모지도_문자_단위로_세고_제어문자는_거절한다() throws Exception {
		Account a = newAccount();
		patchMe(a, "{\"name\":\"" + "😀".repeat(30) + "\"}").andExpect(status().isOk()); // UTF-16으로는 60이지만 30자
		patchMe(a, "{\"name\":\"" + "😀".repeat(31) + "\"}").andExpect(status().isBadRequest());
		patchMe(a, "{\"title\":\"줄\\n바꿈\"}").andExpect(status().isBadRequest());
		patchMe(a, "{\"name\":\"a\\u0000b\"}").andExpect(status().isBadRequest());
		// strip 전 길이가 30을 넘어도 strip 후 30자면 통과
		patchMe(a, "{\"name\":\"  " + "가".repeat(30) + "  \"}").andExpect(status().isOk());
	}

	@Test
	void 재발급_훅이_실패하면_키는_바뀌지_않는다() throws Exception {
		Account a = newAccount();
		String oldKey = JsonPath.read(me(a).andReturn().getResponse().getContentAsString(), "$.streamKey");
		doThrow(new IllegalStateException("kick 실패")).when(terminator).endLiveBroadcastOf(a.channelId());

		mvc.perform(post("/api/channels/me/stream-key/rotate").header("Authorization", "Bearer " + a.access()))
				.andExpect(status().isInternalServerError());

		me(a).andExpect(jsonPath("$.streamKey").value(oldKey));
	}

	@Test
	void 수정과_재발급이_동시에_와도_새_키가_옛_키로_되돌아가지_않는다() throws Exception {
		Account a = newAccount();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			for (int i = 0; i < 8; i++) {
				int n = i;
				Callable<String> rotate = () -> JsonPath.read(mvc.perform(post("/api/channels/me/stream-key/rotate")
						.header("Authorization", "Bearer " + a.access())).andExpect(status().isOk())
						.andReturn().getResponse().getContentAsString(), "$.streamKey");
				Callable<String> patchTitle = () -> {
					patchMe(a, "{\"title\":\"동시 " + n + "\"}").andExpect(status().isOk());
					return null;
				};
				List<Future<String>> futures = new ArrayList<>();
				futures.add(pool.submit(patchTitle));
				Future<String> rotated = pool.submit(rotate);
				futures.add(pool.submit(patchTitle));
				for (Future<String> f : futures) {
					f.get();
				}
				me(a).andExpect(jsonPath("$.streamKey").value(rotated.get()));
			}
		} finally {
			pool.shutdownNow();
		}
	}

	// ---------- GET /channels/{id} ----------

	@Test
	void 채널_공개_조회는_비로그인도_되고_키는_내려가지_않는다() throws Exception {
		Account a = newAccount();
		patchMe(a, "{\"description\":\"안녕\",\"category\":\"MUSIC\"}").andExpect(status().isOk());
		String streamKey = JsonPath.read(me(a).andReturn().getResponse().getContentAsString(), "$.streamKey");

		String body = mvc.perform(get("/api/channels/" + a.channelId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(a.channelId()))
				.andExpect(jsonPath("$.name").value(a.nickname()))
				.andExpect(jsonPath("$.description").value("안녕"))
				.andExpect(jsonPath("$.category.code").value("MUSIC"))
				.andExpect(jsonPath("$.category.label").value("음악"))
				.andExpect(jsonPath("$.liveBroadcastId").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.recordingCount").value(0))
				.andExpect(jsonPath("$.postCount").value(0))
				.andExpect(jsonPath("$.owner.nickname").value(a.nickname()))
				.andExpect(jsonPath("$.streamKey").doesNotExist())
				.andExpect(jsonPath("$.widgetKey").doesNotExist())
				.andExpect(jsonPath("$.publish").doesNotExist())
				.andExpect(jsonPath("$.title").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(streamKey);

		// 다른 회원 토큰으로 봐도 같은 공개 정보
		Account b = newAccount();
		mvc.perform(get("/api/channels/" + a.channelId()).header("Authorization", "Bearer " + b.access()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.streamKey").doesNotExist());
	}

	@Test
	void 없는_채널은_404_숫자가_아닌_id는_400() throws Exception {
		mvc.perform(get("/api/channels/999999999")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
		mvc.perform(get("/api/channels/abc")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	// ---------- 다른 회원 ----------

	@Test
	void 회원은_자기_채널만_보고_고치고_다른_회원의_채널은_바뀌지_않는다() throws Exception {
		Account a = newAccount();
		Account b = newAccount();
		String aKey = JsonPath.read(me(a).andReturn().getResponse().getContentAsString(), "$.streamKey");
		String aWidget = JsonPath.read(me(a).andReturn().getResponse().getContentAsString(), "$.widgetKey");

		// b의 토큰으로 /me는 항상 b의 채널
		me(b).andExpect(jsonPath("$.id").value(b.channelId())).andExpect(jsonPath("$.owner.nickname").value(b.nickname()));
		patchMe(b, "{\"name\":\"b의 채널\",\"title\":\"b 방송\"}").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(b.channelId()));
		mvc.perform(post("/api/channels/me/stream-key/rotate").header("Authorization", "Bearer " + b.access())).andExpect(status().isOk());
		mvc.perform(post("/api/channels/me/widget-key/rotate").header("Authorization", "Bearer " + b.access())).andExpect(status().isOk());
		verify(terminator).endLiveBroadcastOf(b.channelId());

		me(a).andExpect(jsonPath("$.name").value(a.nickname())).andExpect(jsonPath("$.title").value(a.nickname() + "의 방송"))
				.andExpect(jsonPath("$.streamKey").value(aKey)).andExpect(jsonPath("$.widgetKey").value(aWidget));
	}

	@Test
	void 소유자_검증_헬퍼는_다른_회원이면_403_FORBIDDEN() throws Exception {
		Account a = newAccount();
		Account b = newAccount();
		tx.executeWithoutResult(s -> {
			Channel channelOfA = channelRepository.findById(a.channelId()).orElseThrow();
			Long memberIdOfA = channelOfA.getMember().getId();
			MemberPrincipal owner = new MemberPrincipal(memberIdOfA, a.nickname(), a.channelId());
			MemberPrincipal other = new MemberPrincipal(memberIdOfA + 1_000_000, b.nickname(), b.channelId());

			OwnershipGuard.requireChannelOwner(channelOfA, owner); // 통과
			assertThatThrownBy(() -> OwnershipGuard.requireChannelOwner(channelOfA, other))
					.isInstanceOfSatisfying(ApiException.class, e -> {
						assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
						assertThat(e.getErrorCode().getStatus().value()).isEqualTo(403);
					});
			assertThatThrownBy(() -> OwnershipGuard.requireChannelOwner(channelOfA, null))
					.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
		});
	}

	// ---------- helpers ----------

	private ResultActions me(Account account) throws Exception {
		return mvc.perform(get("/api/channels/me").header("Authorization", "Bearer " + account.access()));
	}

	private ResultActions patchMe(Account account, String json) throws Exception {
		return mvc.perform(patch("/api/channels/me").header("Authorization", "Bearer " + account.access())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private Account newAccount() throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String email = "u" + suffix + "@example.com";
		String nickname = "n" + suffix;
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"" + nickname + "\"}"))
				.andExpect(status().isCreated());
		String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new Account(nickname, JsonPath.read(res, "$.accessToken"),
				((Number) JsonPath.read(res, "$.member.channelId")).longValue());
	}
}
