package com.livestream.livestream.post;

import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.common.dto.PageResponse;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.common.security.OwnershipGuard;
import com.livestream.livestream.post.dto.PostResponse;
import com.livestream.livestream.post.dto.PostSummary;
import com.livestream.livestream.post.dto.PostUpdateRequest;
import com.livestream.livestream.post.dto.PostWriteRequest;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 채널 커뮤니티 게시글 (DESIGN §4.12 · §4.16 `/channels/{id}/posts` · `/posts/{id}`). 쓰기는 채널 주인만. */
@Service
@RequiredArgsConstructor
public class PostService {

	static final int TITLE_MAX = 100;
	static final int CONTENT_MAX = 5_000;
	static final int PREVIEW_MAX = 100;
	/** 제목에는 개행 등 제어문자를 허용하지 않는다. 본문은 개행 · 탭만 허용한다. */
	private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");
	private static final Pattern CONTENT_CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r\\t]]");
	private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final PostRepository postRepository;
	private final ChannelRepository channelRepository;

	@Transactional(readOnly = true)
	public PageResponse<PostSummary> list(Long channelId, Integer page, Integer size) {
		if (!channelRepository.existsById(channelId)) {
			throw new ApiException(ErrorCode.CHANNEL_NOT_FOUND);
		}
		Page<Post> posts = postRepository.findByChannelId(channelId, PageResponse.pageable(page, size, LATEST));
		return PageResponse.of(posts, p -> new PostSummary(p.getId(), p.getTitle(), preview(p.getContent()),
				commentCount(p), p.getCreatedAt(), p.getUpdatedAt()));
	}

	@Transactional(readOnly = true)
	public PostResponse get(Long postId, MemberPrincipal me) {
		return toResponse(find(postId), me);
	}

	/** 내 채널에 쓴다. 채널 id를 입력으로 받지 않으므로 남의 채널에 쓸 수 없다. */
	@Transactional
	public PostResponse write(MemberPrincipal me, PostWriteRequest request) {
		if (me == null) {
			throw new ApiException(ErrorCode.UNAUTHENTICATED);
		}
		Channel channel = channelRepository.findByMemberId(me.id())
				.orElseThrow(() -> new ApiException(ErrorCode.CHANNEL_NOT_FOUND));
		OwnershipGuard.requireChannelOwner(channel, me);
		Post post = postRepository.save(Post.write(channel, title(request.title()), content(request.content())));
		return toResponse(post, me);
	}

	@Transactional
	public PostResponse update(MemberPrincipal me, Long postId, PostUpdateRequest request) {
		Post post = find(postId);
		OwnershipGuard.requireChannelOwner(post.getChannel(), me);
		String title = request.title() == null ? null : title(request.title());
		String content = request.content() == null ? null : content(request.content());
		post.edit(title, content);
		postRepository.flush(); // updatedAt 을 응답에 반영
		return toResponse(post, me);
	}

	/** 물리 삭제. 게시글 댓글은 DB FK on delete cascade 로 함께 지워진다(§4.3 comment). */
	@Transactional
	public void delete(MemberPrincipal me, Long postId) {
		Post post = find(postId);
		OwnershipGuard.requireChannelOwner(post.getChannel(), me);
		postRepository.delete(post);
	}

	private Post find(Long postId) {
		return postRepository.findWithChannel(postId).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
	}

	private PostResponse toResponse(Post post, MemberPrincipal me) {
		Channel channel = post.getChannel();
		boolean isOwner = me != null && channel.getMember().getId().equals(me.id());
		return new PostResponse(post.getId(),
				new PostResponse.ChannelRef(channel.getId(), channel.getName(), channel.getMember().getNickname()),
				post.getTitle(), post.getContent(), commentCount(post), post.getCreatedAt(), post.getUpdatedAt(), isOwner);
	}

	/** TODO(CMT-1): 삭제 표시 안 된 댓글 수. */
	private long commentCount(Post post) {
		return 0;
	}

	private static String title(String raw) {
		String value = raw.strip();
		if (value.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "제목을 입력해 주세요");
		}
		if (CONTROL_CHARS.matcher(value).find()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "제목에 사용할 수 없는 문자가 있습니다");
		}
		if (value.codePointCount(0, value.length()) > TITLE_MAX) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "제목은 " + TITLE_MAX + "자 이하여야 합니다");
		}
		return value;
	}

	private static String content(String raw) {
		String value = raw.strip();
		if (value.isEmpty()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "본문을 입력해 주세요");
		}
		if (CONTENT_CONTROL_CHARS.matcher(value).find()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "본문에 사용할 수 없는 문자가 있습니다");
		}
		if (value.codePointCount(0, value.length()) > CONTENT_MAX) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "본문은 " + CONTENT_MAX + "자 이하여야 합니다");
		}
		return value;
	}

	/** 목록용 본문 앞부분. 코드포인트 단위로 자른다(이모지 반쪽 방지). */
	static String preview(String content) {
		int len = content.codePointCount(0, content.length());
		if (len <= PREVIEW_MAX) {
			return content;
		}
		return content.substring(0, content.offsetByCodePoints(0, PREVIEW_MAX));
	}
}
