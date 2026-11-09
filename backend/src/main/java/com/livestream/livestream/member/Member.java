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

	@Column(name = "email", nullable = false, length = 255)
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
