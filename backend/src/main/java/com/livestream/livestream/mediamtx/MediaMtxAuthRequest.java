package com.livestream.livestream.mediamtx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MediaMTX HTTP 인증 요청 본문 (DESIGN §4.9). WHIP {@code Authorization: Bearer} 는 버전에 따라 password 또는 token 으로,
 * RTMP {@code ?user=&pass=} 는 user · password 로 들어온다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MediaMtxAuthRequest(String ip, String user, String password, String token, String path,
		String protocol, String id, String action, String query) {
}
