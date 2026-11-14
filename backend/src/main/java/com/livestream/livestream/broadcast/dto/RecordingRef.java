package com.livestream.livestream.broadcast.dto;

import com.livestream.livestream.recording.Recording;

/** 방송에 딸린 다시보기 상태. */
public record RecordingRef(Long id, String status, String visibility) {

	public static RecordingRef of(Recording r) {
		return r == null ? null : new RecordingRef(r.getId(), r.getStatus().name(), r.getVisibility().name());
	}
}
