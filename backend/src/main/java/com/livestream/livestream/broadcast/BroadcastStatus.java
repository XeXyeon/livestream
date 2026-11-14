package com.livestream.livestream.broadcast;

/** DESIGN §4.6 방송 상태. LIVE ⇄ DISCONNECTED → ENDED. */
public enum BroadcastStatus {
	LIVE,
	DISCONNECTED,
	ENDED;

	/** 채널당 최대 1건인 "진행 중" 상태. */
	public boolean isOngoing() {
		return this != ENDED;
	}
}
