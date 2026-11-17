# 요구사항별 구현 위치

브라우저 미디어 처리(화면 캡처 · 소리 믹싱 · 카메라 송출)는 **프론트엔드(`frontend/`)** 에 있고, 서버는 방송 상태 · 인증 · 채팅 · 후원 · 녹화를 맡습니다. 경로는 저장소 기준입니다. 설계는 [`docs/DESIGN.md`](DESIGN.md)를 보세요.

| 요구사항 | 프론트엔드 | 백엔드 (`backend/src/main/java/com/livestream/livestream/`) | 인프라 |
|---|---|---|---|
| PC 라이브 방송: 화면 캡처 송출 | `frontend/src/media/mixer.ts` `createPcStream` · `frontend/src/pages/StudioPc.tsx` · `frontend/src/media/whip.ts` `WhipClient` | `mediamtx/MediaMtxAuthController` (송출 인증) · `mediamtx/MediaMtxHookController` (송출 시작 · 종료 알림) | `infra/mediamtx.yml` (WHIP 수신) |
| PC 라이브 방송: 탭/시스템 소리 + 마이크 | `frontend/src/media/mixer.ts` `createPcStream` | - | - |
| 모바일 라이브 방송: 카메라 + 마이크 송출 | `frontend/src/media/mixer.ts` `createMobileStream` · `frontend/src/pages/StudioMobile.tsx` · `frontend/src/media/whip.ts` | 위와 같음 | 위와 같음 |
| 방송 관리 (시작 · 정보 수정 · 종료 · 삭제) | `frontend/src/components/studio/useBroadcastSession.ts` · `frontend/src/pages/StudioPc.tsx` · `frontend/src/pages/StudioSettings.tsx` · `frontend/src/pages/Me.tsx` | `broadcast/BroadcastController` · `broadcast/BroadcastLifecycleService` · `channel/ChannelController` | - |
| 시청 (실시간 · 그리드 · 추천) | `frontend/src/pages/Live.tsx` · `frontend/src/components/player/WhepView.tsx` (WHEP) · `frontend/src/components/player/GridView.tsx` (HLS + P2P) | `broadcast/BroadcastController` · `broadcast/RecommendationService` | `infra/mediamtx.yml` (WHEP · HLS) · `infra/tracker/` |
| 회원가입 · 로그인 (인증) | `frontend/src/pages/Login.tsx` · `frontend/src/pages/Signup.tsx` · `frontend/src/store/auth.ts` · `frontend/src/api/client.ts` | `auth/AuthController` · `auth/AuthService` · `common/security/JwtProvider` | - |
| 인가 (본인만 수정 · 삭제) | 응답의 `isOwner`로 버튼 노출 (`frontend/src/components/layout/Protected.tsx`) | `common/security/OwnershipGuard` · `common/config/SecurityConfig` | - |
| 채팅 · 댓글 | `frontend/src/components/chat/` (STOMP) · `frontend/src/components/comment/` | `chat/ChatController` · `common/config/WebSocketConfig` · `comment/CommentController` | - |
| 후원 | `frontend/src/components/donation/` | `donation/DonationController` · `donation/DonationService` | - |
| 녹화 · 다시보기 | `frontend/src/pages/Video.tsx` · `frontend/src/components/recording/` | `recording/RecordingController` · `recording/RecordingService` · `recording/RecordingMergeService` | `infra/mediamtx.yml` (`record: yes`, fMP4 저장) |
| 클립 (라이브 초안 · 구간 선택 · 다시보기 구간) | `frontend/src/components/clip/` (`ClipRangeBar`) · `frontend/src/pages/Clip.tsx` | `clip/ClipController` · `clip/ClipDraftController` · `clip/ClipProcessingService` · `mediamtx/MediaMtxClient` | `infra/mediamtx.yml` (`playback`, 내부 전용) |
| 그리드 모드 (HLS + P2P) | `frontend/src/media/gridPlayer.ts` · `frontend/src/components/player/GridView.tsx` · `PlayerStats.tsx` · `ModeToggle.tsx` | `chat/ViewerCounter` (시청자 수) | `infra/tracker/` |
| 통합 검색 · 팔로우 | `frontend/src/pages/Search.tsx` · `frontend/src/components/sidebar/ChannelSidebar.tsx` | `search/SearchController` · `follow/FollowController` | - |
| 알림 위젯 (SSE) | `frontend/src/pages/Widget.tsx` | `integration/WidgetEventService` | - |
| 탭 제목 · 공유 태그 · robots | `frontend/src/components/layout/usePageMeta.ts` · `frontend/index.html` · `frontend/public/robots.txt` | - | - |

## 송출 구현 요약

- **PC 화면 캡처 송출:** `createPcStream`(`frontend/src/media/mixer.ts`)이 `navigator.mediaDevices.getDisplayMedia({ video, audio: true })`로 화면 영상을 캡처하고, `WhipClient.publish`(`frontend/src/media/whip.ts`)가 `RTCPeerConnection`으로 WebRTC offer를 만들어 WHIP(HTTP POST)로 MediaMTX에 실시간 송출합니다.
- **탭/시스템 소리 + 마이크 동시 송출:** 같은 `createPcStream`이 `getDisplayMedia`로 받은 탭 · 시스템 소리 트랙과 `getUserMedia({ audio })`로 연 마이크 트랙을 `AudioContext`의 `GainNode`로 각각 조절해 `MediaStreamAudioDestinationNode` 하나로 합치고, 이 오디오와 화면 영상을 한 `MediaStream`으로 묶어 보냅니다.
- **모바일 카메라 + 마이크 송출:** `createMobileStream`(같은 파일)이 `getUserMedia({ video: { facingMode }, audio: true })`로 휴대폰 카메라 영상과 마이크 음성을 함께 받아 같은 `WhipClient`로 송출합니다(`frontend/src/pages/StudioMobile.tsx`, 전 · 후면 전환 지원). 화면 공유를 못 하거나 모바일 UA면 `frontend/src/media/devices.ts`의 `studioTarget`이 모바일 스튜디오로 보냅니다.
- **종료 순서:** `useBroadcastSession`(`frontend/src/components/studio/useBroadcastSession.ts`)의 `end`가 `whip.close()` → 스트림 정지 → `POST /api/broadcasts/{id}/end` 순으로 처리합니다.
- **라우트:** `/studio/pc`, `/studio/mobile` (`frontend/src/App.tsx`).
