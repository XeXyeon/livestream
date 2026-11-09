package com.livestream.livestream.auth;

import com.livestream.livestream.common.entity.BaseEntity;
import com.livestream.livestream.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 토큰 원문은 쿠키에만 있고 DB에는 SHA-256 hex만 저장한다.
 * token_hash는 설계서의 일반 index 대신 unique로 둔다: 비유니크 인덱스를 SELECT … FOR UPDATE로 잠그면
 * MySQL이 gap lock까지 잡아 서로 다른 회원의 동시 갱신이 데드락 날 수 있다(unique면 행 잠금만 잡는다).
 */
@Getter
@Entity
@Table(name = "refresh_token", uniqueConstraints = @UniqueConstraint(name = "uk_refresh_token_hash", columnNames = "token_hash"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@Column(name = "token_hash", nullable = false, columnDefinition = "CHAR(64)")
	private String tokenHash;

	@Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME(6)")
	private Instant expiresAt;

	@Column(name = "revoked_at", columnDefinition = "DATETIME(6)")
	private Instant revokedAt;

	/** 회전으로 revoke됐을 때 새 토큰 id. 로그아웃 · 재사용 의심 revoke는 null. */
	@Column(name = "replaced_by_id")
	private Long replacedById;

	public static RefreshToken issue(Member member, String tokenHash, Instant expiresAt) {
		RefreshToken token = new RefreshToken();
		token.member = member;
		token.tokenHash = tokenHash;
		token.expiresAt = expiresAt;
		return token;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isExpired(Instant now) {
		return !expiresAt.isAfter(now);
	}

	public void rotate(Long newTokenId, Instant now) {
		this.revokedAt = now;
		this.replacedById = newTokenId;
	}

	public void revoke(Instant now) {
		this.revokedAt = now;
	}

	/** 회전된 직후 유예 안인지: 탭 여러 개가 같은 토큰으로 동시에 갱신하는 경우를 받아 준다. */
	public boolean isWithinRotationGrace(Instant now, long graceSeconds) {
		return revokedAt != null && replacedById != null && revokedAt.isAfter(now.minusSeconds(graceSeconds));
	}
}
