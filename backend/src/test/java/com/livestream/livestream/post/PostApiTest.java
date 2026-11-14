package com.livestream.livestream.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** POST-1: 게시글 작성 · 목록(최신순) · 상세 · 수정 · 삭제, 주인 외 403 (DESIGN §4.12 · §4.16). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostApiTest {

	@Autowired MockMvc mvc;
	@Autowired PostRepository postRepository;

	@Test
	void 주인이_글을_쓰면_201과_상세가_오고_채널_글_수에_반영된다() throws Exception {
		Account a = TestAccounts.create(mvc);

		String body = write(a, "{\"title\":\"  첫 공지  \",\"content\":\"방송 일정\\n월 · 수 · 금\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.title").value("첫 공지"))
				.andExpect(jsonPath("$.content").value("방송 일정\n월 · 수 · 금"))
				.andExpect(jsonPath("$.channel.id").value(a.channelId()))
				.andExpect(jsonPath("$.channel.ownerNickname").value(a.nickname()))
				.andExpect(jsonPath("$.commentCount").value(0))
				.andExpect(jsonPath("$.isOwner").value(true))
				.andExpect(jsonPath("$.createdAt").exists())
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(body, "$.id")).longValue();

		// 비로그인 상세: isOwner false
		mvc.perform(get("/api/posts/" + id)).andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("첫 공지"))
				.andExpect(jsonPath("$.isOwner").value(false));
		mvc.perform(get("/api/channels/" + a.channelId())).andExpect(jsonPath("$.postCount").value(1));
	}

	@Test
	void 목록은_누구나_최신순으로_본다() throws Exception {
		Account a = TestAccounts.create(mvc);
		write(a, "{\"title\":\"하나\",\"content\":\"1\"}").andExpect(status().isCreated());
		write(a, "{\"title\":\"둘\",\"content\":\"" + "가".repeat(150) + "\"}").andExpect(status().isCreated());
		write(a, "{\"title\":\"셋\",\"content\":\"3\"}").andExpect(status().isCreated());
		// 다른 채널 글은 섞이지 않는다
		Account other = TestAccounts.create(mvc);
		write(other, "{\"title\":\"남의 글\",\"content\":\"x\"}").andExpect(status().isCreated());

		mvc.perform(get("/api/channels/" + a.channelId() + "/posts")).andExpect(status().isOk())
				.andExpect(jsonPath("$.items", hasSize(3)))
				.andExpect(jsonPath("$.items[0].title").value("셋"))
				.andExpect(jsonPath("$.items[1].title").value("둘"))
				.andExpect(jsonPath("$.items[1].preview").value("가".repeat(100)))
				.andExpect(jsonPath("$.items[2].title").value("하나"))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.hasNext").value(false));

		mvc.perform(get("/api/channels/" + a.channelId() + "/posts?page=1&size=2"))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].title").value("하나"))
				.andExpect(jsonPath("$.hasNext").value(false));
		mvc.perform(get("/api/channels/999999999/posts")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
	}

	@Test
	void 주인은_보낸_필드만_수정한다() throws Exception {
		Account a = TestAccounts.create(mvc);
		long id = idOf(write(a, "{\"title\":\"제목\",\"content\":\"본문\"}"));

		patchPost(a, id, "{\"content\":\"고친 본문\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("제목"))
				.andExpect(jsonPath("$.content").value("고친 본문"));
		patchPost(a, id, "{\"title\":\"새 제목\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("새 제목"))
				.andExpect(jsonPath("$.content").value("고친 본문"));
	}

	@Test
	void 다른_회원은_수정_삭제가_403이고_비로그인은_401이다() throws Exception {
		Account a = TestAccounts.create(mvc);
		Account b = TestAccounts.create(mvc);
		long id = idOf(write(a, "{\"title\":\"제목\",\"content\":\"본문\"}"));

		patchPost(b, id, "{\"title\":\"탈취\"}").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(delete("/api/posts/" + id).header("Authorization", b.bearer()))
				.andExpect(status().isForbidden());
		mvc.perform(patch("/api/posts/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/posts/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/channels/me/posts").contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"t\",\"content\":\"c\"}")).andExpect(status().isUnauthorized());

		// b 가 /channels/me/posts 로 쓰면 b 자기 채널에 들어간다(남의 채널에 쓸 길이 없다)
		write(b, "{\"title\":\"내 글\",\"content\":\"c\"}").andExpect(status().isCreated())
				.andExpect(jsonPath("$.channel.id").value(b.channelId()));

		assertThat(postRepository.findById(id)).get().extracting(Post::getTitle).isEqualTo("제목");
	}

	@Test
	void 주인이_삭제하면_204이고_이후_404다() throws Exception {
		Account a = TestAccounts.create(mvc);
		long id = idOf(write(a, "{\"title\":\"제목\",\"content\":\"본문\"}"));

		mvc.perform(delete("/api/posts/" + id).header("Authorization", a.bearer())).andExpect(status().isNoContent());
		mvc.perform(get("/api/posts/" + id)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
		mvc.perform(delete("/api/posts/" + id).header("Authorization", a.bearer())).andExpect(status().isNotFound());
		assertThat(postRepository.existsById(id)).isFalse();
	}

	@Test
	void 제목_본문_검증() throws Exception {
		Account a = TestAccounts.create(mvc);
		write(a, "{\"title\":\"   \",\"content\":\"본문\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		write(a, "{\"title\":\"줄\\n바꿈\",\"content\":\"본문\"}").andExpect(status().isBadRequest());
		write(a, "{\"title\":\"" + "가".repeat(101) + "\",\"content\":\"본문\"}").andExpect(status().isBadRequest());
		write(a, "{\"title\":\"제목\",\"content\":\"" + "가".repeat(5001) + "\"}").andExpect(status().isBadRequest());
		write(a, "{\"title\":\"제목\"}").andExpect(status().isBadRequest());
		write(a, "{\"title\":\"" + "가".repeat(100) + "\",\"content\":\"" + "가".repeat(5000) + "\"}")
				.andExpect(status().isCreated());

		long id = idOf(write(a, "{\"title\":\"제목\",\"content\":\"본문\"}"));
		patchPost(a, id, "{\"content\":\"  \"}").andExpect(status().isBadRequest());
	}

	private ResultActions write(Account account, String json) throws Exception {
		return mvc.perform(post("/api/channels/me/posts").header("Authorization", account.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions patchPost(Account account, long id, String json) throws Exception {
		return mvc.perform(patch("/api/posts/" + id).header("Authorization", account.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static long idOf(ResultActions result) throws Exception {
		String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}
}
