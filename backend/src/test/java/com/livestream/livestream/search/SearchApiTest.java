package com.livestream.livestream.search;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.livestream.livestream.broadcast.Broadcast;
import com.livestream.livestream.broadcast.BroadcastRepository;
import com.livestream.livestream.broadcast.BroadcastSource;
import com.livestream.livestream.broadcast.BroadcastStatus;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.chat.ViewerCounter;
import com.livestream.livestream.recording.Recording;
import com.livestream.livestream.recording.RecordingRepository;
import com.livestream.livestream.recording.Visibility;
import com.livestream.livestream.support.TestAccounts;
import com.livestream.livestream.support.TestAccounts.Account;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SEARCH-1 통합 테스트 (DESIGN §4.16 /search · §9 SEARCH-1): 제목 · 채널 이름 · 닉네임 매칭, 대소문자 무시, 와일드카드 이스케이프,
 * 제외 조건(ENDED · 삭제 · 비공개 · READY 아님), q 검증 · 정규화, 요약 5개 + total, 페이징, 정렬, 채널 요약 필드, 404, 비로그인 200.
 * 다른 테스트가 같은 DB에 남긴 행과 섞이지 않게 테스트마다 무작위 토큰을 검색어로 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchApiTest {

	@Autowired MockMvc mvc;
	@Autowired BroadcastRepository broadcastRepository;
	@Autowired ChannelRepository channelRepository;
	@Autowired RecordingRepository recordingRepository;
	@Autowired TransactionTemplate tx;
	@Autowired EntityManager em;
	@MockitoBean ViewerCounter viewerCounter;

	// ---------- 매칭 ----------

	@Test
	void 채널_이름으로_찾아지고_대소문자는_무시한다() throws Exception {
		String tok = token();
		Account a = account(tok + " Alpha", tok + " 소개");

		search(tok.toUpperCase() + " alpha").andExpect(status().isOk())
				.andExpect(jsonPath("$.q").value(tok.toUpperCase() + " alpha"))
				.andExpect(jsonPath("$.channels.total").value(1))
				.andExpect(jsonPath("$.channels.items[0].id").value(a.channelId()))
				.andExpect(jsonPath("$.channels.items[0].name").value(tok + " Alpha"))
				.andExpect(jsonPath("$.channels.items[0].description").value(tok + " 소개"))
				.andExpect(jsonPath("$.channels.items[0].category.code").value("ETC"))
				.andExpect(jsonPath("$.lives.total").value(0))
				.andExpect(jsonPath("$.recordings.total").value(0));
	}

	@Test
	void 주인_닉네임으로도_채널이_찾아진다() throws Exception {
		String tok = token();
		Account a = TestAccounts.create(mvc);
		String nick = a.nickname();
		rename(a, "전혀 다른 이름 " + tok, null);

		search(nick).andExpect(status().isOk())
				.andExpect(jsonPath("$.channels.total").value(1))
				.andExpect(jsonPath("$.channels.items[0].id").value(a.channelId()))
				.andExpect(jsonPath("$.channels.items[0].name").value("전혀 다른 이름 " + tok));
	}

	@Test
	void 라이브는_방송_제목_또는_채널_이름으로_찾아진다() throws Exception {
		String tok = token();
		Account byTitle = account("제목용 " + tok.substring(0, 4), null);
		setTitle(byTitle, "방송 " + tok);
		long titleBroadcast = startLive(byTitle);
		Account byName = account("이름 " + tok, null);
		setTitle(byName, "아무 제목");
		long nameBroadcast = startLive(byName);

		search(tok).andExpect(status().isOk())
				.andExpect(jsonPath("$.lives.total").value(2))
				.andExpect(jsonPath("$.lives.items.length()").value(2))
				// 시청자 수가 같으면(모두 0) 시작이 늦은 쪽이 먼저
				.andExpect(jsonPath("$.lives.items[0].id").value(nameBroadcast))
				.andExpect(jsonPath("$.lives.items[0].status").value("LIVE"))
				.andExpect(jsonPath("$.lives.items[1].id").value(titleBroadcast))
				.andExpect(jsonPath("$.lives.items[1].channel.id").value(byTitle.channelId()));
	}

	@Test
	void 다시보기는_방송_제목_또는_채널_이름으로_찾아진다() throws Exception {
		String tok = token();
		Account byTitle = account("제목용 " + tok.substring(0, 4), null);
		setTitle(byTitle, "다시보기 " + tok);
		long titleRecording = recording(byTitle, Instant.now().minusSeconds(60), true, true, false);
		Account byName = account("이름 " + tok, null);
		setTitle(byName, "아무 제목");
		long nameRecording = recording(byName, Instant.now(), true, true, false);

		search(tok).andExpect(status().isOk())
				.andExpect(jsonPath("$.recordings.total").value(2))
				// 방송 시작 최신순
				.andExpect(jsonPath("$.recordings.items[0].id").value(nameRecording))
				.andExpect(jsonPath("$.recordings.items[1].id").value(titleRecording))
				.andExpect(jsonPath("$.recordings.items[1].title").value("다시보기 " + tok))
				.andExpect(jsonPath("$.recordings.items[1].commentCount").value(0));
	}

	// ---------- 제외 조건 ----------

	@Test
	void 종료되었거나_삭제된_방송은_라이브에_없고_DISCONNECTED는_있다() throws Exception {
		String tok = token();
		Account live = account(tok + " live", null);
		long liveId = startLive(live);
		Account disconnected = account(tok + " disc", null);
		long discId = startLive(disconnected);
		setStatus(discId, BroadcastStatus.DISCONNECTED);
		Account ended = account(tok + " ended", null);
		setStatus(startLive(ended), BroadcastStatus.ENDED);
		Account deleted = account(tok + " deleted", null);
		markDeleted(startLive(deleted));

		search(tok).andExpect(status().isOk())
				.andExpect(jsonPath("$.lives.total").value(2))
				.andExpect(jsonPath("$.lives.items[?(@.id == " + liveId + ")]").exists())
				.andExpect(jsonPath("$.lives.items[?(@.id == " + discId + ")].status").value("DISCONNECTED"))
				// 채널은 방송 상태와 무관하게 4개 모두 찾아진다
				.andExpect(jsonPath("$.channels.total").value(4));
	}

	@Test
	void 비공개_READY_아님_삭제된_방송의_다시보기는_다시보기에_없다() throws Exception {
		String tok = token();
		Instant at = Instant.now();
		long open = recording(account(tok + " open", null), at, true, true, false);
		recording(account(tok + " private", null), at, false, true, false);
		recording(account(tok + " processing", null), at, true, false, false);
		recording(account(tok + " deleted", null), at, true, true, true);

		search(tok).andExpect(status().isOk())
				.andExpect(jsonPath("$.recordings.total").value(1))
				.andExpect(jsonPath("$.recordings.items[0].id").value(open));
	}

	// ---------- q 규칙 ----------

	@Test
	void 퍼센트와_밑줄과_역슬래시는_와일드카드가_아니라_글자로_찾는다() throws Exception {
		String tok = token();
		account(tok + "50%off", null);
		account(tok + "50xoff", null);
		account(tok + "a_b", null);
		account(tok + "aXb", null);
		account(tok + "p\\q", null);
		account(tok + "pq", null);

		search(tok + "50%").andExpect(jsonPath("$.channels.total").value(1))
				.andExpect(jsonPath("$.channels.items[0].name").value(tok + "50%off"));
		search(tok + "a_b").andExpect(jsonPath("$.channels.total").value(1))
				.andExpect(jsonPath("$.channels.items[0].name").value(tok + "a_b"));
		search(tok + "p\\q").andExpect(jsonPath("$.channels.total").value(1))
				.andExpect(jsonPath("$.channels.items[0].name").value(tok + "p\\q"));
		search("%").andExpect(status().isOk()); // 전부가 아니라 '%' 글자를 찾는다(오류 없이 200)
	}

	@Test
	void 앞뒤_공백과_연속_공백은_정규화되어_찾아진다() throws Exception {
		String tok = token();
		account(tok + " 한 칸", null);

		search("   " + tok + "     한    칸  ").andExpect(status().isOk())
				.andExpect(jsonPath("$.q").value(tok + " 한 칸"))
				.andExpect(jsonPath("$.channels.total").value(1));
	}

	@Test
	void 빈_q와_공백뿐인_q와_51자는_400이다() throws Exception {
		for (String q : new String[] {"", "   ", "\t", "a".repeat(51)}) {
			search(q).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
					.andExpect(jsonPath("$.message").value("검색어는 1~50자"));
			mvc.perform(get("/api/search/channels").param("q", q)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		}
		mvc.perform(get("/api/search")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		search("a".repeat(50)).andExpect(status().isOk());
	}

	// ---------- 요약 · 페이징 · 정렬 ----------

	@Test
	void 요약은_종류별_최대_5개이고_total은_전체_수이다() throws Exception {
		String tok = token();
		for (int i = 0; i < 7; i++) {
			Account a = account(tok + " c" + i, null);
			if (i < 6) {
				recording(a, Instant.now().minusSeconds(100 - i), true, true, false);
			}
			if (i < 6) {
				startLive(a);
			}
		}

		search(tok).andExpect(status().isOk())
				.andExpect(jsonPath("$.channels.total").value(7))
				.andExpect(jsonPath("$.channels.items.length()").value(5))
				// 채널은 이름 오름차순
				.andExpect(jsonPath("$.channels.items[0].name").value(tok + " c0"))
				.andExpect(jsonPath("$.channels.items[4].name").value(tok + " c4"))
				.andExpect(jsonPath("$.lives.total").value(6))
				.andExpect(jsonPath("$.lives.items.length()").value(5))
				.andExpect(jsonPath("$.recordings.total").value(6))
				.andExpect(jsonPath("$.recordings.items.length()").value(5));
	}

	@Test
	void 종류별_목록은_페이징된다() throws Exception {
		String tok = token();
		for (int i = 0; i < 6; i++) {
			Account a = account(tok + " r" + i, null);
			recording(a, Instant.now().minusSeconds(100 - i), true, true, false);
			startLive(a);
		}

		for (String type : new String[] {"recordings", "lives", "channels"}) {
			page(type, tok, 0, 4).andExpect(status().isOk())
					.andExpect(jsonPath("$.items.length()").value(4))
					.andExpect(jsonPath("$.page").value(0))
					.andExpect(jsonPath("$.size").value(4))
					.andExpect(jsonPath("$.totalElements").value(6))
					.andExpect(jsonPath("$.hasNext").value(true));
			page(type, tok, 1, 4).andExpect(status().isOk())
					.andExpect(jsonPath("$.items.length()").value(2))
					.andExpect(jsonPath("$.page").value(1))
					.andExpect(jsonPath("$.hasNext").value(false));
			page(type, tok, 2, 4).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		}
		// 다시보기 최신순
		page("recordings", tok, 0, 20).andExpect(jsonPath("$.items[0].channel.name").value(tok + " r5"));
		// size 는 최대 50 으로 자른다
		page("channels", tok, 0, 500).andExpect(jsonPath("$.size").value(50));
	}

	@Test
	void 라이브는_시청자_수_많은_순으로_정렬된다() throws Exception {
		String tok = token();
		Account few = account(tok + " few", null);
		Account many = account(tok + " many", null);
		Account mid = account(tok + " mid", null);
		long fewId = startLive(few);
		long manyId = startLive(many);
		long midId = startLive(mid);
		when(viewerCounter.count(fewId)).thenReturn(1L);
		when(viewerCounter.count(manyId)).thenReturn(30L);
		when(viewerCounter.count(midId)).thenReturn(7L);

		search(tok).andExpect(jsonPath("$.lives.items[0].id").value(manyId))
				.andExpect(jsonPath("$.lives.items[0].viewerCount").value(30))
				.andExpect(jsonPath("$.lives.items[1].id").value(midId))
				.andExpect(jsonPath("$.lives.items[2].id").value(fewId));
		page("lives", tok, 0, 2).andExpect(jsonPath("$.items[0].id").value(manyId))
				.andExpect(jsonPath("$.items[1].id").value(midId))
				.andExpect(jsonPath("$.hasNext").value(true));
	}

	@Test
	void 채널_요약은_라이브_여부_시청자_수_팔로워_수를_담는다() throws Exception {
		String tok = token();
		Account liveOwner = account(tok + " a-live", null);
		Account offline = account(tok + " b-off", null);
		Account fan1 = TestAccounts.create(mvc);
		Account fan2 = TestAccounts.create(mvc);
		long liveId = startLive(liveOwner);
		when(viewerCounter.count(liveId)).thenReturn(12L);
		follow(fan1, liveOwner);
		follow(fan2, liveOwner);
		follow(fan1, offline);

		search(tok).andExpect(status().isOk())
				.andExpect(jsonPath("$.channels.items[0].id").value(liveOwner.channelId()))
				.andExpect(jsonPath("$.channels.items[0].isLive").value(true))
				.andExpect(jsonPath("$.channels.items[0].liveBroadcastId").value(liveId))
				.andExpect(jsonPath("$.channels.items[0].viewerCount").value(12))
				.andExpect(jsonPath("$.channels.items[0].followerCount").value(2))
				.andExpect(jsonPath("$.channels.items[1].id").value(offline.channelId()))
				.andExpect(jsonPath("$.channels.items[1].isLive").value(false))
				.andExpect(jsonPath("$.channels.items[1].liveBroadcastId").doesNotExist())
				.andExpect(jsonPath("$.channels.items[1].viewerCount").value(0))
				.andExpect(jsonPath("$.channels.items[1].followerCount").value(1));
	}

	// ---------- 경로 · 권한 ----------

	@Test
	void 모르는_type은_404이다() throws Exception {
		mvc.perform(get("/api/search/members").param("q", "abc")).andExpect(status().isNotFound());
		mvc.perform(get("/api/search/Channels").param("q", "abc")).andExpect(status().isNotFound());
	}

	@Test
	void 비로그인도_검색할_수_있다() throws Exception {
		mvc.perform(get("/api/search").param("q", token())).andExpect(status().isOk());
		mvc.perform(get("/api/search/channels").param("q", token())).andExpect(status().isOk());
		mvc.perform(get("/api/search/lives").param("q", token())).andExpect(status().isOk());
		mvc.perform(get("/api/search/recordings").param("q", token())).andExpect(status().isOk());
	}

	// ---------- 도우미 ----------

	private static String token() {
		return "z" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}

	private ResultActions search(String q) throws Exception {
		return mvc.perform(get("/api/search").param("q", q));
	}

	private ResultActions page(String type, String q, int page, int size) throws Exception {
		return mvc.perform(get("/api/search/" + type).param("q", q).param("page", String.valueOf(page))
				.param("size", String.valueOf(size)));
	}

	private void follow(Account fan, Account owner) throws Exception {
		mvc.perform(post("/api/channels/" + owner.channelId() + "/follow").header("Authorization", fan.bearer()))
				.andExpect(status().isOk());
	}

	/** 새 회원을 만들고 채널 이름 · 소개를 바꾼다. */
	private Account account(String channelName, String description) throws Exception {
		Account a = TestAccounts.create(mvc);
		rename(a, channelName, description);
		return a;
	}

	private void rename(Account a, String name, String description) {
		tx.executeWithoutResult(s -> channelRepository.findById(a.channelId()).orElseThrow()
				.updateInfo(name, description, null, null, null));
	}

	/** 다음 방송 제목(방송 시작 때 복사된다). */
	private void setTitle(Account a, String title) {
		tx.executeWithoutResult(s -> channelRepository.findById(a.channelId()).orElseThrow()
				.updateInfo(null, null, title, null, null));
	}

	private long startLive(Account a) {
		return startLive(a, Instant.now());
	}

	private long startLive(Account a, Instant startedAt) {
		Long id = tx.execute(s -> broadcastRepository.save(
				Broadcast.start(channelRepository.getReferenceById(a.channelId()), BroadcastSource.BROWSER_PC, startedAt))
				.getId());
		return id;
	}

	private void setStatus(long broadcastId, BroadcastStatus status) {
		tx.executeWithoutResult(s -> em.createQuery("update Broadcast b set b.status = :st where b.id = :id")
				.setParameter("st", status).setParameter("id", broadcastId).executeUpdate());
	}

	private void markDeleted(long broadcastId) {
		tx.executeWithoutResult(s -> em.createQuery("update Broadcast b set b.deletedAt = :now where b.id = :id")
				.setParameter("now", Instant.now()).setParameter("id", broadcastId).executeUpdate());
	}

	/** 끝난 방송 + 다시보기를 직접 만든다. visibility · READY 여부 · 삭제 여부를 고른다. 같은 시각이면 id 순이라 시작 시각을 구분해 준다. */
	private long recording(Account a, Instant startedAt, boolean isPublic, boolean ready, boolean deleted) {
		Long id = tx.execute(s -> {
			Broadcast b = broadcastRepository.save(Broadcast.endedForSeed(
					channelRepository.getReferenceById(a.channelId()), BroadcastSource.BROWSER_PC, startedAt,
					startedAt.plusSeconds(30)));
			Recording r = Recording.startFor(b, isPublic);
			if (ready) {
				r.markReady("vod/" + UUID.randomUUID() + ".mp4", 30, 1000L);
			}
			recordingRepository.save(r);
			if (deleted) {
				em.createQuery("update Broadcast b set b.deletedAt = :now where b.id = :id")
						.setParameter("now", Instant.now()).setParameter("id", b.getId()).executeUpdate();
			}
			return r.getId();
		});
		return id;
	}
}
