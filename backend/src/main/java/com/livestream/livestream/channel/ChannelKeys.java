package com.livestream.livestream.channel;

import java.security.SecureRandom;
import java.util.HexFormat;

/** 스트림 키 · 위젯 키 생성: SecureRandom 16바이트 → 32자리 hex (DESIGN §9 CH-1). */
public final class ChannelKeys {

	private static final SecureRandom RANDOM = new SecureRandom();

	private ChannelKeys() {
	}

	public static String generate() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}
}
