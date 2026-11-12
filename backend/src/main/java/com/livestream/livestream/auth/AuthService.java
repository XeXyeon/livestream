package com.livestream.livestream.auth;

import com.livestream.livestream.auth.dto.AuthResponse;
import com.livestream.livestream.auth.dto.LoginRequest;
import com.livestream.livestream.auth.dto.MemberSummary;
import com.livestream.livestream.auth.dto.SignupRequest;
import com.livestream.livestream.channel.Channel;
import com.livestream.livestream.channel.ChannelRepository;
import com.livestream.livestream.common.config.AppProperties;
import com.livestream.livestream.common.error.ApiException;
import com.livestream.livestream.common.error.ErrorCode;
import com.livestream.livestream.common.security.JwtProvider;
import com.livestream.livestream.common.security.MemberPrincipal;
import com.livestream.livestream.member.Member;
import com.livestream.livestream.member.MemberRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가입 · 로그인 · 갱신(회전) · 로그아웃 (DESIGN §4.7). */
@Service
public class AuthService {

	/** BCrypt는 72바이트까지만 읽는다. 그보다 긴 비밀번호는 잘려서 비교되므로 받지 않는다. */
	private static final int BCRYPT_MAX_BYTES = 72;

	private final MemberRepository memberRepository;
	private final ChannelRepository channelRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtProvider jwtProvider;
	private final AppProperties props;
	private final SecureRandom secureRandom = new SecureRandom();
	/** 존재하지 않는 이메일로 로그인할 때도 BCrypt 비교를 한 번 수행해 응답 시간 차이로 가입 여부가 드러나지 않게 한다. */
	private final String dummyPasswordHash;

	public AuthService(MemberRepository memberRepository, ChannelRepository channelRepository,
			RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder, JwtProvider jwtProvider,
			AppProperties props) {
		this.memberRepository = memberRepository;
		this.channelRepository = channelRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtProvider = jwtProvider;
		this.props = props;
		this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
	}

	@Transactional
	public Long signup(SignupRequest request) {
		String email = normalizeEmail(request.email());
		String nickname = request.nickname().strip();
		if (nickname.length() < 2 || nickname.length() > 20) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "닉네임은 2~20자여야 합니다");
		}
		if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR, "비밀번호가 너무 깁니다");
		}
		if (memberRepository.existsByEmail(email)) {
			throw new ApiException(ErrorCode.EMAIL_DUPLICATED);
		}
		if (memberRepository.existsByNickname(nickname)) {
			throw new ApiException(ErrorCode.NICKNAME_DUPLICATED);
		}
		try {
			Member member = memberRepository.saveAndFlush(
					Member.create(email, passwordEncoder.encode(request.password()), nickname));
			channelRepository.save(Channel.createFor(member));
			return member.getId();
		} catch (DataIntegrityViolationException e) {
			// 사전 검사와 저장 사이에 같은 값이 먼저 들어온 경우(동시 가입)
			String cause = String.valueOf(e.getMostSpecificCause().getMessage());
			throw new ApiException(cause.contains("uk_member_nickname")
					? ErrorCode.NICKNAME_DUPLICATED : ErrorCode.EMAIL_DUPLICATED);
		}
	}

	@Transactional
	public AuthResult login(LoginRequest request) {
		Member member = memberRepository.findByEmail(normalizeEmail(request.email())).orElse(null);
		String hash = member != null ? member.getPasswordHash() : dummyPasswordHash;
		boolean tooLong = request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
		boolean matches = !tooLong && passwordEncoder.matches(request.password(), hash);
		if (member == null || !matches) {
			throw new ApiException(ErrorCode.LOGIN_FAILED);
		}
		return issue(member).result();
	}

	/**
	 * refresh 토큰을 회전한다. 재사용 의심으로 401을 던질 때도 "전부 revoke"가 커밋돼야 하므로 롤백하지 않는다.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public AuthResult refresh(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			throw new ApiException(ErrorCode.TOKEN_INVALID);
		}
		RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
				.orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));
		Instant now = Instant.now();
		if (token.isExpired(now)) {
			throw new ApiException(ErrorCode.TOKEN_EXPIRED);
		}
		Member member = token.getMember();
		boolean rotatedBefore = token.isRevoked();
		if (rotatedBefore && !token.isWithinRotationGrace(now, props.jwt().refreshRotationGraceSeconds())) {
			// 회전 유예 밖에서 revoke된 토큰이 다시 왔다 = 탈취 의심(또는 로그아웃된 토큰). 그 회원의 토큰을 전부 폐기한다.
			refreshTokenRepository.revokeAllByMemberId(member.getId(), now);
			throw new ApiException(ErrorCode.TOKEN_INVALID);
		}
		Issued issued = issue(member);
		if (!rotatedBefore) {
			// 유예 중인 재요청은 이미 첫 회전 기록(replaced_by_id)이 있으므로 덮어쓰지 않는다.
			token.rotate(issued.token().getId(), now);
		}
		return issued.result();
	}

	/** 그 회원의 refresh 토큰을 전부 revoke한다. 쿠키가 없거나 모르는 토큰이어도 에러 없이 끝난다(멱등). */
	@Transactional
	public void logout(String rawToken) {
		if (rawToken == null || rawToken.isBlank()) {
			return;
		}
		refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
				.ifPresent(t -> refreshTokenRepository.revokeAllByMemberId(t.getMember().getId(), Instant.now()));
	}

	private record Issued(AuthResult result, RefreshToken token) {
	}

	private Issued issue(Member member) {
		Channel channel = channelRepository.findByMemberId(member.getId())
				.orElseThrow(() -> new ApiException(ErrorCode.CHANNEL_NOT_FOUND));
		MemberPrincipal principal = new MemberPrincipal(member.getId(), member.getNickname(), channel.getId());
		String accessToken = jwtProvider.createAccessToken(principal);

		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant expiresAt = Instant.now().plus(props.jwt().refreshDays(), ChronoUnit.DAYS);
		RefreshToken saved = refreshTokenRepository.saveAndFlush(RefreshToken.issue(member, hash(raw), expiresAt));

		AuthResponse response = new AuthResponse(accessToken,
				new MemberSummary(member.getId(), member.getNickname(), channel.getId()));
		return new Issued(new AuthResult(response, raw), saved);
	}

	private static String normalizeEmail(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	static String hash(String raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
