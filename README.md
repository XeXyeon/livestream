# 그리드라이브 (GridLive)

**설치 없이 브라우저 하나로 방송하고, 시청자가 늘면 시청자끼리 영상을 나눠 받아 서버 부담을 줄이는 실시간 라이브 방송 웹 서비스입니다.**

| 바로가기 | |
|---|---|
| **서비스 주소** | **https://gridlive.duckdns.org** |
| **시연** | [`docs/demo.md`](docs/demo.md) — 기능별 GIF 14개 · 전체 시연 영상(mp4) |
| **테스트 계정 · 기능 확인** | [`docs/guide.md`](docs/guide.md) — 방송인 · 시청자 계정(비밀번호 공용), PC · 모바일 송출 → 시청 → 클립 → 종료 순서, 지원 환경 |
| **실행 방법** | [`docs/setup.md`](docs/setup.md) — 실행 환경 · 환경변수 예시 · 운영 배포 · 로컬 개발 |

![송출 시작 → 시청](docs/demo/gif/04-broadcast-watch.gif)

## 한눈에 보기

| | |
|---|---|
| 송출 | PC 화면 + 탭 · 시스템 소리 + 마이크 믹싱, 휴대폰 카메라 + 마이크, OBS · Larix(스트림 키, WHIP · RTMP) |
| 시청 | WebRTC(WHEP) 저지연 재생, 시청자가 많으면 **그리드 모드**(HLS + 시청자 간 P2P)로 자동 전환 |
| 소통 | 실시간 채팅(STOMP), 토스페이먼츠 후원(채팅 강조 · 화면 알림 · OBS 알림 위젯 + 음성 읽기), 다시보기 댓글, 채널 커뮤니티, 팔로우 |
| 남기기 | 자동 녹화 → 다시보기, **라이브 중 최근 2분에서 구간을 골라 만드는 클립**, 다시보기 구간 클립 |
| 찾기 | 헤더 통합 검색(채널 · 라이브 · 다시보기), 카테고리 필터, 종료 화면 추천, 페이지별 탭 제목 · 공유 태그 |
| 품질 | 백엔드 테스트 283개 · 프론트 테스트 202개(31개 파일), 운영 서버에서 실제 브라우저로 송출 · 시청 · 클립 · 다시보기 재생 확인 (2026-11-16 기준) |

## 구성

```
브라우저(React SPA)                      OBS · Larix(스트림 키)
   │ HTTPS                                   │ RTMP / WHIP
   ▼                                         ▼
 Caddy(443) ──/api, /ws──▶ Spring Boot ──▶ MySQL
   │  ──/live/*(WHIP · WHEP · HLS)──▶ MediaMTX ──녹화(fMP4)──▶ 다시보기 · 클립
   │  ──/tracker──▶ wt-tracker   ← 그리드 P2P 피어 소개
   └──/* ──▶ 프론트 정적 빌드
 MediaMTX ──인증 콜백 · 훅──▶ Spring   Spring ──승인──▶ 토스페이먼츠
```

같은 출처 구성이라 CORS 설정이 없습니다. `backend/`는 Spring Boot(Java), `frontend/`는 React + Vite + TypeScript, `infra/`는 docker compose · Caddy · MediaMTX · tracker입니다.

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/demo.md`](docs/demo.md) | 시연: 기능별 GIF 14개(홈 · 로그인 · 송출 · 시청 · 채팅 · 그리드 · 휴대폰 · 종료 · 다시보기 · 채널 · 클립 · 휴대폰 송출), 전체 시연 영상 내려받기 |
| [`docs/guide.md`](docs/guide.md) | 사용 안내: 테스트 계정, 지원 환경, PC · 모바일 송출 · 시청 · 클립 · 종료 순서 |
| [`docs/features.md`](docs/features.md) | 어필 포인트 5가지(그리드 모드 · 브라우저 스튜디오 · 라이브 클립 · 보안 · 동시성 · 설계서 기반 개발), 기능 목록 |
| [`docs/setup.md`](docs/setup.md) | 실행 방법: 실행 환경, 환경변수 예시, 운영 배포, 로컬 개발, 테스트 명령 |
| [`docs/code-map.md`](docs/code-map.md) | 요구사항별 구현 위치(프론트 · 백엔드 · 인프라 파일), 송출 구현 요약 |
| [`docs/credits.md`](docs/credits.md) | 오픈소스 · 외부 서비스 출처와 라이선스 |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 설계서: 테이블 · API · 화면 · 항목별 구현 지침 · 실행 확인 기록 |
| [`infra/README.md`](infra/README.md) | 인프라 상세(compose · Caddy · MediaMTX 설정) |
| [`AGENTS.md`](AGENTS.md) | AI 코딩 에이전트 안내 |
