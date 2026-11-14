package com.livestream.livestream.member;

import com.livestream.livestream.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "member", uniqueConstraints = {
		@UniqueConstraint(name = "uk_member_email", columnNames = "email"),
		@UniqueConstraint(name = "uk_member_nickname", columnNames = "nickname")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseEntity {

	/**
	 * 바이너리 콜레이션 (AUTH-1#4): MySQL 기본 utf8mb4_0900_ai_ci 는 악센트 · 전각 · 대소문자를 같은 글자로 봐
	 * "victim" 과 "vīctim" 이 같은 회원으로 조회된다. 가입 · 로그인이 이미 strip + 소문자로 정규화하므로
	 * 정확 일치(bin)로 비교해도 대소문자 문제는 없다. ddl-auto=update 는 기존 컬럼의 콜레이션을 바꾸지 않으므로
	 * 이미 만들어진 로컬 DB 는 {@code ALTER TABLE member MODIFY email VARCHAR(255) COLLATE utf8mb4_bin NOT NULL} 이 필요하다.
	 */
	@Column(name = "email", nullable = false, columnDefinition = "VARCHAR(255) COLLATE utf8mb4_bin")
	private String email;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "nickname", nullable = false, length = 20)
	private String nickname;

	public static Member create(String email, String passwordHash, String nickname) {
		Member member = new Member();
		member.email = email;
		member.passwordHash = passwordHash;
		member.nickname = nickname;
		return member;
	}
}
