package com.livestream.livestream.broadcast;

/** 송출 수단 (DESIGN §4.3 · §4.6). ready 훅의 sourceType · query로 정한다. */
public enum BroadcastSource {
	BROWSER_PC,
	BROWSER_MOBILE,
	STREAM_KEY
}
