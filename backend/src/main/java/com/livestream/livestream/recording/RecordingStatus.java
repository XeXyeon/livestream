package com.livestream.livestream.recording;

/** DESIGN §4.3 · §4.10 녹화 상태. */
public enum RecordingStatus {
	RECORDING,
	PROCESSING,
	READY,
	FAILED,
	FILE_DELETED
}
