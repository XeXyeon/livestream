# 그리드라이브 (GridLive)

브라우저만으로 방송하고, 시청자가 늘면 시청자끼리 나눠 보는 실시간 라이브 방송 웹 서비스입니다.

> 서비스 주소 · 테스트 계정 · 실행 방법 · 기능 확인 절차 · 시연 영상은 배포를 마친 뒤 이 문서에 채웁니다.

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
