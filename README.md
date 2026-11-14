# 그리드라이브 (GridLive)

브라우저만으로 방송하고, 시청자가 늘면 시청자끼리 나눠 보는 실시간 라이브 방송 웹 서비스입니다.

> 서비스 주소 · 실행 방법 · 기능 확인 절차 · 시연 영상은 배포를 마친 뒤 이 문서에 채웁니다.

## 요구사항별 구현 위치

브라우저 미디어 처리(화면 캡처 · 소리 믹싱 · 카메라 송출)는 **프론트엔드(`frontend/`)** 에 있고, 서버는 방송 상태 · 인증 · 채팅 · 후원 · 녹화를 맡습니다. 경로는 저장소 기준입니다.

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

### 송출 구현 요약

- **PC 화면 캡처 송출:** `createPcStream`(`frontend/src/media/mixer.ts`)이 `navigator.mediaDevices.getDisplayMedia({ video, audio: true })`로 화면 영상을 캡처하고, `WhipClient.publish`(`frontend/src/media/whip.ts`)가 `RTCPeerConnection`으로 WebRTC offer를 만들어 WHIP(HTTP POST)로 MediaMTX에 실시간 송출합니다.
- **탭/시스템 소리 + 마이크 동시 송출:** 같은 `createPcStream`이 `getDisplayMedia`로 받은 탭 · 시스템 소리 트랙과 `getUserMedia({ audio })`로 연 마이크 트랙을 `AudioContext`의 `GainNode`로 각각 조절해 `MediaStreamAudioDestinationNode` 하나로 합치고, 이 오디오와 화면 영상을 한 `MediaStream`으로 묶어 보냅니다.
- **모바일 카메라 + 마이크 송출:** `createMobileStream`(같은 파일)이 `getUserMedia({ video: { facingMode }, audio: true })`로 휴대폰 카메라 영상과 마이크 음성을 함께 받아 같은 `WhipClient`로 송출합니다(`frontend/src/pages/StudioMobile.tsx`, 전 · 후면 전환 지원). 화면 공유를 못 하거나 모바일 UA면 `frontend/src/media/devices.ts`의 `studioTarget`이 모바일 스튜디오로 보냅니다.
- **종료 순서:** `useBroadcastSession`(`frontend/src/components/studio/useBroadcastSession.ts`)의 `end`가 `whip.close()` → 스트림 정지 → `POST /api/broadcasts/{id}/end` 순으로 처리합니다.
- **라우트:** `/studio/pc`, `/studio/mobile` (`frontend/src/App.tsx`).

## 테스트 계정

배포된 서비스에는 아래 시연용 계정이 미리 들어 있습니다. 이 비밀번호는 시연 전용이며 다른 곳에서 쓰지 않습니다.

| 역할 | 이메일 | 비밀번호 | 미리 들어 있는 것 |
|---|---|---|---|
| 방송인 | `demo-streamer@example.com` | `gridlive-demo-2026` | 채널 "데모 방송인", 어제 방송의 공개 다시보기 1개, 게시글 "첫 공지" |
| 시청자 | `demo-viewer@example.com` | `gridlive-demo-2026` | 다시보기 댓글 1개 |

- 다른 기기에서 시청을 확인할 때는 방송인 계정으로 송출하고, 시청자 계정 또는 비로그인 상태로 채널 페이지를 엽니다.
- 직접 가입한 새 계정으로도 모든 기능을 쓸 수 있습니다.

## 오픈소스 · 외부 서비스 출처

이 프로젝트는 아래 오픈소스를 의존성 또는 컨테이너 이미지로 사용합니다. 소스 코드를 복사해 넣은 것은 없으며, 각 라이선스는 해당 저장소를 따릅니다.

### 스트리밍 · 인프라
| 이름 | 용도 | 라이선스 | 출처 |
|---|---|---|---|
| MediaMTX 1.21.3 (`bluenviron/mediamtx:1.21.3-ffmpeg`) | 미디어 서버: WHIP 송출 · WHEP 시청 · RTMP · LL-HLS · 녹화 | MIT | https://github.com/bluenviron/mediamtx |
| wt-tracker (커밋 `33127a5`) | 그리드 모드 피어 소개용 WebTorrent 트래커 | Apache-2.0 | https://github.com/Novage/wt-tracker |
| Caddy 2 (`caddy:2`) | HTTPS(자동 인증서) · 리버스 프록시 · 정적 파일 | Apache-2.0 | https://github.com/caddyserver/caddy |
| MySQL 8.4 (`mysql:8.4`) | 데이터베이스 | GPL-2.0 | https://github.com/mysql/mysql-server |
| FFmpeg | 녹화 조각 병합 · 썸네일 · 길이 측정 | LGPL-2.1+ / GPL-2.0+ | https://ffmpeg.org |

### 백엔드 (`backend/`)
| 이름 | 용도 | 라이선스 | 출처 |
|---|---|---|---|
| Spring Boot 4.1 (Web MVC · Security · Data JPA · Validation · WebSocket) | 서버 프레임워크 · 인증 · 채팅(STOMP) | Apache-2.0 | https://github.com/spring-projects/spring-boot |
| springdoc-openapi 3.1 | Swagger UI · OpenAPI 문서 | Apache-2.0 | https://github.com/springdoc/springdoc-openapi |
| JJWT 0.12 | JWT 발급 · 검증 | Apache-2.0 | https://github.com/jwtk/jjwt |
| MySQL Connector/J | DB 드라이버 | GPL-2.0 (Universal FOSS Exception) | https://github.com/mysql/mysql-connector-j |

### 프론트엔드 (`frontend/`)
| 이름 | 용도 | 라이선스 | 출처 |
|---|---|---|---|
| React 19 · React DOM | UI | MIT | https://github.com/facebook/react |
| React Router 7 | 라우팅 | MIT | https://github.com/remix-run/react-router |
| TanStack Query 5 | 서버 상태 · 캐시 | MIT | https://github.com/TanStack/query |
| Zustand 5 | 인증 상태 | MIT | https://github.com/pmndrs/zustand |
| ASTRYX (`@astryxdesign/core` · `theme-neutral` · `cli`) | UI 컴포넌트 · 디자인 토큰 | MIT | https://github.com/meta/astryx |
| StyleX | ASTRYX 스타일 런타임 | MIT | https://github.com/facebook/stylex |
| Tailwind CSS 4 | 유틸리티 CSS | MIT | https://github.com/tailwindlabs/tailwindcss |
| lucide-react | 아이콘 | ISC | https://github.com/lucide-icons/lucide |
| STOMP.js 7 | 채팅 · 방송 이벤트(WebSocket STOMP) | Apache-2.0 | https://github.com/stomp-js/stompjs |
| hls.js 1.6 | 그리드 모드 HLS 재생 | Apache-2.0 | https://github.com/video-dev/hls.js |
| p2p-media-loader 2 (`core` · `hlsjs`) | 그리드 모드 시청자 간 P2P 세그먼트 공유 | Apache-2.0 | https://github.com/Novage/p2p-media-loader |
| 토스페이먼츠 SDK 2 | 후원 결제위젯(테스트 모드) | MIT | https://github.com/tosspayments/browser-sdk |
| Vite 7 · Vitest · TypeScript · ESLint · Testing Library | 빌드 · 테스트 · 정적 검사 | MIT / Apache-2.0 | 각 저장소 |

### 외부 서비스
| 이름 | 용도 |
|---|---|
| 토스페이먼츠 결제위젯 v2 (테스트 모드) | 후원 결제 요청 · 서버 승인(`/v1/payments/confirm`) |
| Let's Encrypt (Caddy 자동 발급) | 운영 도메인 HTTPS 인증서 |
