package com.livestream.livestream.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 통합 테스트용: 가입 → 로그인으로 새 회원(채널 자동 생성)을 만든다. */
public final class TestAccounts {

	public static final String PASSWORD = "password123";

	public record Account(long memberId, String nickname, String access, long channelId) {

		public String bearer() {
			return "Bearer " + access;
		}
	}

	private TestAccounts() {
	}

	public static Account create(MockMvc mvc) throws Exception {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String email = "u" + suffix + "@example.com";
		String nickname = "n" + suffix;
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"" + nickname + "\"}"))
				.andExpect(status().isCreated());
		String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return new Account(((Number) JsonPath.read(res, "$.member.id")).longValue(), nickname,
				JsonPath.read(res, "$.accessToken"), ((Number) JsonPath.read(res, "$.member.channelId")).longValue());
	}
}
