package com.livestream.livestream.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.livestream.livestream.broadcast.BroadcastLifecycleService;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.broadcast.PublishBlockRegistry;
import com.livestream.livestream.mediamtx.MediaMtxClient;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.Visibility;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** CMT-1 (DESIGN §4.12 · §4.8 · §9 CMT-1) · POST-1 댓글 cascade. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommentApiTest {

	@Autowired MockMvc mvc;
	@Autowired BroadcastLifecycleService lifecycle;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired RecordingRepository recordingRepository;
	@Autowired CommentRepository commentRepository;
	@Autowired TransactionTemplate tx;
	@MockitoBean MediaMtxClient mediaMtxClient;
	@Autowired PublishBlockRegistry publishBlocks;

	@Test
	void 다시보기에_댓글과_대댓글을_쓰면_평면_목록으로_오고_댓글_수에_반영된다() throws Exception {
		Account owner = streamer();
		Account viewer = TestAccounts.create(mvc);
		long recordingId = endedRecording(owner);

		long root = id(write(viewer, recordings(recordingId), "{\"content\":\"  재밌었어요  \"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.content").value("재밌었어요"))
				.andExpect(jsonPath("$.parentId").doesNotExist())
				.andExpect(jsonPath("$.deleted").value(false))
				.andExpect(jsonPath("$.author.id").value(viewer.memberId()))
				.andExpect(jsonPath("$.author.nickname").value(viewer.nickname()))
				.andExpect(jsonPath("$.author.isChannelOwner").value(false))
				.andExpect(jsonPath("$.createdAt").exists()));
		long reply = id(write(owner, recordings(recordingId), "{\"content\":\"고마워요\",\"parentId\":" + root + "}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.parentId").value(root))
				.andExpect(jsonPath("$.author.isChannelOwner").value(true)));

		// 비로그인도 본다. createdAt 오름차순 평면 목록
		mvc.perform(get(recordings(recordingId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.items[0].id").value(root))
				.andExpect(jsonPath("$.items[1].id").value(reply))
				.andExpect(jsonPath("$.items[1].parentId").value(root));
		mvc.perform(get("/api/recordings/" + recordingId)).andExpect(jsonPath("$.commentCount").value(2));
	}

	@Test
	void 대댓글에_답글은_409_다른_대상의_댓글에_답글은_400() throws Exception {
		Account owner = streamer();
		Account viewer = TestAccounts.create(mvc);
		long recordingId = endedRecording(owner);
		long otherRecordingId = endedRecording(streamer()); // 같은 채널은 종료 뒤 30초 송출 차단
		long postId = writePost(owner);

		long root = id(write(viewer, recordings(recordingId), "{\"content\":\"부모\"}"));
		long reply = id(write(viewer, recordings(recordingId), "{\"content\":\"자식\",\"parentId\":" + root + "}"));

		write(viewer, recordings(recordingId), "{\"content\":\"손자\",\"parentId\":" + reply + "}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMENT_DEPTH_EXCEEDED"));
		write(viewer, recordings(otherRecordingId), "{\"content\":\"다른 다시보기\",\"parentId\":" + root + "}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		write(viewer, posts(postId), "{\"content\":\"게시글로\",\"parentId\":" + root + "}")
				.andExpect(status().isBadRequest());
		write(viewer, recordings(recordingId), "{\"content\":\"없는 부모\",\"parentId\":999999999}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
	}

	@Test
	void 수정_삭제는_작성자만_채널_주인도_남의_댓글은_403() throws Exception {
		Account owner = streamer();
		Account author = TestAccounts.create(mvc);
		Account other = TestAccounts.create(mvc);
		long recordingId = endedRecording(owner);
		long id = id(write(author, recordings(recordingId), "{\"content\":\"원래\"}"));

		for (Account notAuthor : new Account[] {owner, other}) {
			edit(notAuthor, id, "{\"content\":\"바꿈\"}").andExpect(status().isForbidden());
			mvc.perform(delete("/api/comments/" + id).header(HttpHeaders.AUTHORIZATION, notAuthor.bearer()))
					.andExpect(status().isForbidden());
		}
		mvc.perform(patch("/api/comments/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/comments/" + id)).andExpect(status().isUnauthorized());

		edit(author, id, "{\"content\":\" 고침 \"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.content").value("고침"));
		mvc.perform(delete("/api/comments/" + id).header(HttpHeaders.AUTHORIZATION, author.bearer()))
				.andExpect(status().isNoContent());
		// 지운 댓글은 다시 고치거나 지울 수 없다
		edit(author, id, "{\"content\":\"되살리기\"}").andExpect(status().isNotFound());
		mvc.perform(delete("/api/comments/" + id).header(HttpHeaders.AUTHORIZATION, author.bearer()))
				.andExpect(status().isNotFound());
	}

	@Test
	void 대댓글이_있는_댓글은_지워도_삭제된_댓글로_남고_없으면_목록에서_빠진다() throws Exception {
		Account owner = streamer();
		Account viewer = TestAccounts.create(mvc);
		long recordingId = endedRecording(owner);
		long withReply = id(write(viewer, recordings(recordingId), "{\"content\":\"답글 달린 글\"}"));
		long reply = id(write(owner, recordings(recordingId), "{\"content\":\"답글\",\"parentId\":" + withReply + "}"));
		long alone = id(write(viewer, recordings(recordingId), "{\"content\":\"혼자\"}"));

		remove(viewer, withReply);
		remove(viewer, alone);

		mvc.perform(get(recordings(recordingId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.items[0].id").value(withReply))
				.andExpect(jsonPath("$.items[0].deleted").value(true))
				.andExpect(jsonPath("$.items[0].content").doesNotExist())
				.andExpect(jsonPath("$.items[1].id").value(reply));
		mvc.perform(get("/api/recordings/" + recordingId)).andExpect(jsonPath("$.commentCount").value(1));
		// 삭제된 댓글에는 답글을 달 수 없다
		write(owner, recordings(recordingId), "{\"content\":\"또\",\"parentId\":" + withReply + "}")
				.andExpect(status().isNotFound());

		// 마지막 대댓글까지 지우면 부모도 목록에서 빠진다
		remove(owner, reply);
		mvc.perform(get(recordings(recordingId))).andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void 녹화가_READY_FILE_DELETED일_때만_댓글을_쓴다() throws Exception {
		Account owner = streamer();
		Account viewer = TestAccounts.create(mvc);
		lifecycle.onReady(owner.channelId(), BroadcastSource.BROWSER_PC);
		long broadcastId = broadcastRepository.findOngoing(owner.channelId()).orElseThrow().getId();
		long recordingId = recordingRepository.findByBroadcastId(broadcastId).orElseThrow().getId();

		write(viewer, recordings(recordingId), "{\"content\":\"라이브 중\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RECORDING_COMMENT_NOT_OPEN"));

		lifecycle.endOngoingOf(owner.channelId());
		tx.executeWithoutResult(s -> recordingRepository.updateVisibility(recordingId, Visibility.PUBLIC, Instant.now()));
		// 병합 중(PROCESSING) · 실패(FAILED)도 409
		write(viewer, recordings(recordingId), "{\"content\":\"병합 중\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RECORDING_COMMENT_NOT_OPEN"));
		tx.executeWithoutResult(s -> recordingRepository.findById(recordingId).orElseThrow().markFailed("TEST"));
		write(viewer, recordings(recordingId), "{\"content\":\"실패\"}").andExpect(status().isConflict());
		// 목록은 상태와 무관하게 본다
		mvc.perform(get(recordings(recordingId))).andExpect(status().isOk());

		tx.executeWithoutResult(s -> recordingRepository.findById(recordingId).orElseThrow()
				.markReady("vod/" + UUID.randomUUID() + ".mp4", 12, 1000));
		write(viewer, recordings(recordingId), "{\"content\":\"준비됨\"}").andExpect(status().isCreated());
		tx.executeWithoutResult(s -> recordingRepository.findById(recordingId).orElseThrow().markFileDeleted(Instant.now()));
		write(viewer, recordings(recordingId), "{\"content\":\"보관 만료 뒤\"}").andExpect(status().isCreated());
	}

	@Test
	void 다시보기_목록의_댓글_수는_다시보기별로_센다() throws Exception {
		Account viewer = TestAccounts.create(mvc);
		long two = endedRecording(streamer());
		long one = endedRecording(streamer());
		long none = endedRecording(streamer());
		long root = id(write(viewer, recordings(two), "{\"content\":\"a\"}"));
		write(viewer, recordings(two), "{\"content\":\"b\",\"parentId\":" + root + "}");
		long deleted = id(write(viewer, recordings(two), "{\"content\":\"지울 것\"}"));
		remove(viewer, deleted);
		write(viewer, recordings(one), "{\"content\":\"c\"}");

		String body = mvc.perform(get("/api/recordings").param("size", "50")).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(commentCountOf(body, two)).isEqualTo(2);
		assertThat(commentCountOf(body, one)).isEqualTo(1);
		assertThat(commentCountOf(body, none)).isZero();
	}

	@Test
	void 비공개_다시보기의_댓글은_주인만_보고_쓴다() throws Exception {
		Account owner = streamer();
		Account viewer = TestAccounts.create(mvc);
		long recordingId = endedRecording(owner);
		long id = id(write(viewer, recordings(recordingId), "{\"content\":\"공개일 때\"}"));
		tx.executeWithoutResult(s -> recordingRepository.updateVisibility(recordingId, Visibility.PRIVATE, Instant.now()));

		write(viewer, recordings(recordingId), "{\"content\":\"비공개\"}").andExpect(status().isForbidden());
		mvc.perform(get(recordings(recordingId)).header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
				.andExpect(status().isForbidden());
		mvc.perform(get(recordings(recordingId))).andExpect(status().isForbidden());
		// 비공개가 된 뒤 작성자는 고칠 수 없고 지울 수는 있다
		edit(viewer, id, "{\"content\":\"고침\"}").andExpect(status().isForbidden());

		mvc.perform(get(recordings(recordingId)).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1));
		write(owner, recordings(recordingId), "{\"content\":\"주인\"}").andExpect(status().isCreated());
		remove(viewer, id);
	}

	@Test
	void 없는_대상은_404_비로그인_작성은_401_내용_검증은_400() throws Exception {
		Account owner = streamer();
		long recordingId = endedRecording(owner);

		mvc.perform(get(recordings(999_999_999L))).andExpect(status().isNotFound());
		mvc.perform(get(posts(999_999_999L))).andExpect(status().isNotFound());
		write(owner, recordings(999_999_999L), "{\"content\":\"x\"}").andExpect(status().isNotFound());
		write(owner, posts(999_999_999L), "{\"content\":\"x\"}").andExpect(status().isNotFound());
		mvc.perform(post(recordings(recordingId)).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
				.andExpect(status().isUnauthorized());

		write(owner, recordings(recordingId), "{\"content\":\"   \"}").andExpect(status().isBadRequest());
		write(owner, recordings(recordingId), "{}").andExpect(status().isBadRequest());
		write(owner, recordings(recordingId), "{\"content\":\"a\\u0000b\"}").andExpect(status().isBadRequest());
		write(owner, recordings(recordingId), "{\"content\":\"" + "가".repeat(501) + "\"}").andExpect(status().isBadRequest());
		write(owner, recordings(recordingId), "{\"content\":\"" + "😀".repeat(500) + "\"}").andExpect(status().isCreated());
	}

	@Test
	void 게시글_댓글을_쓰고_글_목록_상세의_댓글_수에_반영된다() throws Exception {
		Account owner = streamer();
		Account viewer = TestAccounts.create(mvc);
		long postId = writePost(owner);
		long root = id(write(viewer, posts(postId), "{\"content\":\"좋은 글\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.author.isChannelOwner").value(false)));
		write(owner, posts(postId), "{\"content\":\"감사\",\"parentId\":" + root + "}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.author.isChannelOwner").value(true));

		mvc.perform(get(posts(postId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.items[1].parentId").value(root));
		mvc.perform(get("/api/posts/" + postId)).andExpect(jsonPath("$.commentCount").value(2));
		mvc.perform(get("/api/channels/" + owner.channelId() + "/posts")).andExpect(jsonPath("$.items[0].commentCount").value(2));
	}

	@Test
	void 게시글을_지우면_댓글과_대댓글도_지워진다() throws Exception {
		Account owner = streamer();
		Account viewer = TestAccounts.create(mvc);
		long postId = writePost(owner);
		long root = id(write(viewer, posts(postId), "{\"content\":\"댓글\"}"));
		long reply = id(write(owner, posts(postId), "{\"content\":\"답글\",\"parentId\":" + root + "}"));

		mvc.perform(delete("/api/posts/" + postId).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
				.andExpect(status().isNoContent());

		assertThat(commentRepository.existsById(root)).isFalse();
		assertThat(commentRepository.existsById(reply)).isFalse();
		write(viewer, posts(postId), "{\"content\":\"늦은 댓글\"}").andExpect(status().isNotFound());
	}

	@Test
	void 방송을_지우면_다시보기_댓글이_물리_삭제된다() throws Exception {
		Account owner = streamer();
		Account viewer = TestAccounts.create(mvc);
		long recordingId = endedRecording(owner);
		long broadcastId = recordingRepository.findById(recordingId).orElseThrow().getBroadcast().getId();
		long root = id(write(viewer, recordings(recordingId), "{\"content\":\"댓글\"}"));
		long reply = id(write(owner, recordings(recordingId), "{\"content\":\"답글\",\"parentId\":" + root + "}"));

		mvc.perform(delete("/api/broadcasts/" + broadcastId).header(HttpHeaders.AUTHORIZATION, owner.bearer()))
				.andExpect(status().isNoContent());

		assertThat(commentRepository.existsById(root)).isFalse();
		assertThat(commentRepository.existsById(reply)).isFalse();
		mvc.perform(get(recordings(recordingId))).andExpect(status().isNotFound());
		write(viewer, recordings(recordingId), "{\"content\":\"늦은 댓글\"}").andExpect(status().isNotFound());
	}

	// ---------- 도우미 ----------

	/**
	 * 방송을 열 수 있는 계정. 캐시된 다른 테스트 컨텍스트가 같은 DB 를 create-drop 으로 다시 만들면 id 가 1부터 다시 매겨져,
	 * 새 계정의 채널 id 가 이 컨텍스트의 송출 차단(종료 뒤 30초) 목록에 남은 id 와 겹칠 수 있다 → 차단되지 않은 계정을 고른다.
	 */
	private Account streamer() throws Exception {
		for (int i = 0; i < 50; i++) {
			Account a = TestAccounts.create(mvc);
			if (!publishBlocks.isBlocked(a.channelId())) {
				return a;
			}
		}
		throw new IllegalStateException("송출 차단되지 않은 채널을 만들지 못했다");
	}

	/** 방송 시작 → 종료(PROCESSING) → READY(파일은 만들지 않는다 — 댓글에는 필요 없다) → 공개. 병합은 10초 뒤라 겹치지 않는다. */
	private long endedRecording(Account a) {
		long id = processingRecording(a);
		tx.executeWithoutResult(s -> recordingRepository.findById(id).orElseThrow()
				.markReady("vod/" + UUID.randomUUID() + ".mp4", 12, 1000));
		tx.executeWithoutResult(s -> recordingRepository.updateVisibility(id, Visibility.PUBLIC, Instant.now()));
		return id;
	}

	private long processingRecording(Account a) {
		lifecycle.onReady(a.channelId(), BroadcastSource.BROWSER_PC);
		long broadcastId = broadcastRepository.findOngoing(a.channelId()).orElseThrow().getId();
		lifecycle.endOngoingOf(a.channelId());
		return recordingRepository.findByBroadcastId(broadcastId).orElseThrow().getId();
	}

	private long writePost(Account owner) throws Exception {
		return id(mvc.perform(post("/api/channels/me/posts").header(HttpHeaders.AUTHORIZATION, owner.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"공지\",\"content\":\"본문\"}"))
				.andExpect(status().isCreated()));
	}

	private static String recordings(long id) {
		return "/api/recordings/" + id + "/comments";
	}

	private static String posts(long id) {
		return "/api/posts/" + id + "/comments";
	}

	private ResultActions write(Account a, String path, String body) throws Exception {
		return mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, a.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions edit(Account a, long id, String body) throws Exception {
		return mvc.perform(patch("/api/comments/" + id).header(HttpHeaders.AUTHORIZATION, a.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void remove(Account a, long id) throws Exception {
		mvc.perform(delete("/api/comments/" + id).header(HttpHeaders.AUTHORIZATION, a.bearer()))
				.andExpect(status().isNoContent());
	}

	private static long commentCountOf(String page, long recordingId) {
		List<Number> counts = JsonPath.read(page, "$.items[?(@.id == " + recordingId + ")].commentCount");
		assertThat(counts).as("목록에 다시보기 %d 가 있어야 한다", recordingId).hasSize(1);
		return counts.getFirst().longValue();
	}

	private static long id(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}
}
