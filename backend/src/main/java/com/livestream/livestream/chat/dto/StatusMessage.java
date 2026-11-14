package com.livestream.livestream.chat.dto;

import com.livestream.livestream.broadcast.BroadcastStatus;
import java.time.Instant;

/** 토픽 {@code {type:"status", status, at}}. */
public record StatusMessage(String type, BroadcastStatus status, Instant at) {

	public static StatusMessage of(BroadcastStatus status, Instant at) {
		return new StatusMessage("status", status, at);
	}
}
