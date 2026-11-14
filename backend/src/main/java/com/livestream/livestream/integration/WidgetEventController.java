package com.livestream.livestream.integration;

import com.livestream.livestream.common.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Integration", description = "외부 연동(알림 위젯 · OBS 브라우저 소스)")
@RestController
@RequiredArgsConstructor
public class WidgetEventController {

	private final WidgetConnectService connectService;
	private final WidgetEventService eventService;

	@Operation(summary = "후원 알림 SSE", description = "인증 없음(위젯 키). event: ready → event: donation(id = 후원 id), 15초마다 ': ping'. "
			+ "Last-Event-ID 헤더 또는 lastEventId 쿼리(둘 다면 헤더 우선)가 오면 그 뒤 승인 후원(최대 100건)을 먼저 보낸다. "
			+ "위젯 키를 재발급하면 연결이 끊긴다. 없는 키는 404")
	@GetMapping(value = "/api/public/widget/{widgetKey}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> events(@PathVariable String widgetKey,
			@RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
			@RequestParam(value = "lastEventId", required = false) String lastEventIdParam) {
		// 쿼리는 프론트가 CLOSED 뒤 새 EventSource 를 열 때 쓴다(헤더를 붙일 수 없다, FE-9#1). 숫자가 아니면 prepare 가 무시한다.
		String lastEventId = lastEventIdHeader != null ? lastEventIdHeader : lastEventIdParam;
		WidgetConnectService.Connection connection;
		try {
			connection = connectService.prepare(widgetKey, lastEventId);
		} catch (ApiException e) {
			// EventSource 는 Accept: text/event-stream 만 보내 JSON 에러 본문과 협상이 안 된다(전역 처리기로 가면 500).
			// 본문 없이 상태 코드만 준다 — EventSource 는 200 이 아니면 재연결하지 않는다.
			return ResponseEntity.status(e.getErrorCode().getStatus()).build();
		}
		SseEmitter emitter = eventService.connect(connection.channelId(), connection.replay());
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header("X-Accel-Buffering", "no") // 프록시가 이벤트를 모아 두지 않게
				.contentType(MediaType.TEXT_EVENT_STREAM)
				.body(emitter);
	}
}
