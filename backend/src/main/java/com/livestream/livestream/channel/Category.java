package com.livestream.livestream.channel;

import lombok.Getter;

/** DESIGN §4.3 enum. 응답에는 code와 label 둘 다 내려 준다. */
@Getter
public enum Category {
	GAME("게임"),
	TALK("토크"),
	MUSIC("음악"),
	STUDY("공부"),
	ETC("기타");

	private final String label;

	Category(String label) {
		this.label = label;
	}
}
