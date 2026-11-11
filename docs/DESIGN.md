# 그리드라이브(가칭) 설계서

실시간 방송 플랫폼(라이브 송출 · 시청 웹 서비스)의 구현 기준 문서. 구현 담당 에이전트는 **이 문서와 자기 로드맵 항목만으로** 작업할 수 있어야 한다. 결정 근거는 작업 카드(`~/.work-dash/worklogs/livestream/ARCH-1.md`, `ARCH-2.md`, `PLAN-1.md`)에 있고, 이 문서와 카드가 다르면 **이 문서가 우선**한다(발견하면 설계자에게 알린다).

- 마감: 2026-11-19 23:59. 1인 + AI. 범위: 공모 체크리스트 필수 + 선택 전부 + 추가 기능(채팅 · 그리드 · 스트림 키 송출 · 커뮤니티 · 알림 위젯).
- 서비스 이름은 미정(가칭 "그리드라이브 / GridLive"). 코드 식별자에는 이름을 넣지 않는다.

## 0. 구현 에이전트 사용법

1. 로드맵 `~/.work-dash/roadmaps/livestream.md`에서 맡은 항목 ID를 확인하고 `/roadmap start <ID>`로 시작한다. 작업 카드 작성 규칙은 `~/.work-dash/ROADMAP-FORMAT.md`.
2. 이 문서에서 **§9 항목별 구현 지침**의 자기 ID 절을 읽고, 거기서 가리키는 §절을 읽는다. 다른 절은 필요할 때만.
3. 이름(테이블 · 컬럼 · 엔드포인트 · 패키지 · 파일 · 설정 키)은 이 문서의 것을 **그대로** 쓴다. 바꿔야 하면 설계자에게 묻는다.
4. 구현과 그 테스트는 같은 커밋. 한 항목은 보통 2~4커밋(`~/.work-dash/RULES.md` 커밋 규칙).
5. 설계에 없는 큰 판단(새 테이블, 새 외부 서비스, 흐름 변경)은 하지 말고 카드 "열린 질문"에 적고 설계자에게 묻는다.
6. 하지 말 것: 설계 문서를 저장소에 더 만들기, 의존성 임의 추가(§9에 적힌 것만), `ddl-auto` 변경, 비밀 값 커밋.

## 1. 요구사항 (공모 체크리스트 ↔ 구현)

| 체크 항목 | 구현 위치 |
|---|---|
| PC 화면 캡처 송출 / 탭·시스템 소리 + 마이크 / 마이크 | §7.5 `mixer.ts` + `whip.ts` (FE-2) |
| 시작 · 종료 시 송출 연결 생성 · 종료 | WHIP 연결(FE-2) · `POST /api/broadcasts/{id}/end` + kick(BC-1) |
| 모바일 카메라 + 마이크 송출 / 시작 · 종료 | §7.5 (FE-3) |
| 방송 정보 CRUD | `PATCH /api/channels/me`, `/api/broadcasts` (CH-1 · BC-2) |
| 목록 · 상세, 다른 기기 수신 · 재생 | `GET /api/broadcasts`, WHEP/LL-HLS (BC-2 · FE-4 · GRID-2) |
| 방송중 · 종료 상태, 종료 시 송출 중단 | §4.6 상태 전이 (BC-1) |
| 영구 저장 · 재시작 후 조회 | MySQL 볼륨 (OPS-3) |
| 가입 · 로그인 · 로그아웃 · 인증 상태 | §4.7 (AUTH-1 · FE-1) |
| 비로그인 조회 · 시청, 등록 · 송출은 로그인 | §4.8 권한 |
| 수정 · 삭제 · 송출 제어 시 서버 소유자 검증 | §4.8 + `OwnerAuthorizationTest` |
| 댓글 CRUD · 작성자 검증 (선택) | §4.12 (CMT-1) — 근거는 **다시보기 댓글** |
| 후원 요청 · 검증 저장 · 중복 승인 방지 · 내역 합계 (선택) | §4.13 (DON-1) + `DonationConfirmIdempotencyTest` |
| 녹화 저장 · 목록 · 재생 · 영구 저장 (선택) | §4.10 (REC-1 · REC-2) |

## 2. 시스템 구성

```
브라우저(React SPA)                      OBS · Larix(스트림 키)
   │ HTTPS                                   │ RTMP 1935 / WHIP
   ▼                                         ▼
 Caddy(443) ──/api, /ws──▶ Spring Boot(8080) ──JDBC──▶ MySQL(3306)
   │  ──/live/*(whip·whep·hls)──▶ MediaMTX(8889 webrtc · 8888 hls · 1935 rtmp · 8554 rtsp · 9997 api · 8189/udp ice)
   │  ──/vod/*, /uploads/*──▶ 정적 볼륨
   │  ──/tracker──▶ wt-tracker(8000)   ← 그리드 P2P 피어 소개
   └──/* ──▶ 프론트 정적 빌드
 MediaMTX ──훅(curl)·인증 콜백──▶ Spring /api/internal/mediamtx/*
 Spring ──Control API──▶ MediaMTX :9997   Spring ──RTSP(ffmpeg)──▶ MediaMTX :8554
 Spring ──HTTPS──▶ Toss Payments(승인)
```

### 2.1 docker compose 서비스 (`infra/compose.yaml`)
| 서비스 | 이미지 | 포트(외부) | 볼륨 |
|---|---|---|---|
| `caddy` | `caddy:2` | 80, 443 | `caddy_data`, `./Caddyfile`, `media`(읽기), `uploads`(읽기), `frontend/dist` |
| `backend` | `backend/Dockerfile`(temurin 25 + `apk add ffmpeg`) | — (내부 8080) | `media`(`/recordings`), `uploads`(`/uploads`) |
| `mysql` | `mysql:8.4` | — (로컬 개발은 3306) | `mysql_data` |
| `mediamtx` | `bluenviron/mediamtx:latest` | 1935/tcp, 8189/udp | `media`(`/recordings`), `./mediamtx.yml` |
| `tracker` | `node:22-alpine` + `wt-tracker` | — (내부 8000) | — |

로컬 개발: `backend`는 IDE에서 실행하고 compose는 `mysql · mediamtx · caddy · tracker`만(`compose.dev.yaml`, Caddy가 `localhost:8080` · `localhost:5173`으로 프록시). 운영: 전부 compose.

### 2.2 Caddyfile 라우팅
| 경로 | 대상 | 비고 |
|---|---|---|
| `/api/*` | `backend:8080` | `flush_interval -1`(SSE) |
| `/ws` | `backend:8080` | WebSocket 업그레이드 |
| `/live/*` | `mediamtx:8889`(`/whip`, `/whep` 끝) · `mediamtx:8888`(`.m3u8`, `.mp4`, `.m4s`, `.ts`) · **그 외는 `/*`와 같은 프론트 서빙** | 경로 끝으로 분기. SPA 시청 화면 `/live/:channelId`(§6.3)와 접두어가 겹치므로, 미디어 모양이 아닌 경로는 404가 아니라 index.html을 내준다(정적 모드 `try_files`, dev 모드 Vite 프록시 모두). 주소창으로 바로 열거나 새로고침해도 시청 화면이 떠야 한다(OPS-3#1) |
| `/vod/*` | 정적 `/srv/media/vod` | mp4, Range. **공개 영상만** 이 폴더에 있다. 비공개 영상은 `/srv/media/private`에 있어 Caddy가 내주지 않는다(§4.10) |
| `/uploads/*` | 정적 `/srv/uploads` | 썸네일 |
| `/tracker` | `tracker:8000` | WebSocket |
| `/*` | 정적 `/srv/frontend` + `try_files {path} /index.html` | SPA |

### 2.3 MediaMTX 설정 (`infra/mediamtx.yml` 핵심)
```yaml
api: yes
apiAddress: :9997
rtsp: yes            # 썸네일 캡처용(내부)
rtmp: yes
hls: yes
hlsVariant: lowLatency
hlsPartDuration: 500ms
hlsSegmentDuration: 1s
hlsAlwaysRemux: yes  # 시청자 없어도 HLS 준비(그리드 전환 지연 방지)
webrtc: yes
webrtcAddress: :8889
webrtcLocalUDPAddress: :8189
webrtcAdditionalHosts: [${PUBLIC_HOST}]   # 공인 IP 또는 도메인
authMethod: http
authHTTPAddress: http://backend:8080/api/internal/mediamtx/auth
authHTTPExclude:
  - action: api        # Control API는 내부망만
  - action: metrics
  - action: pprof
pathDefaults:
  overridePublisher: yes      # 두 번째 송출 연결이 기존을 교체
  record: yes
  recordPath: /recordings/raw/%path/%Y-%m-%d_%H-%M-%S-%f
  recordFormat: fmp4
  recordPartDuration: 1s
  recordSegmentDuration: 6h
  recordDeleteAfter: 0s
  runOnReady: curl -s -X POST -H "X-Internal-Token: ${INTERNAL_TOKEN}" -H "Content-Type: application/json" -d '{"path":"$MTX_PATH","sourceType":"$MTX_SOURCE_TYPE","query":"$MTX_QUERY"}' http://backend:8080/api/internal/mediamtx/ready
  runOnNotReady: curl -s -X POST -H "X-Internal-Token: ${INTERNAL_TOKEN}" -H "Content-Type: application/json" -d '{"path":"$MTX_PATH"}' http://backend:8080/api/internal/mediamtx/not-ready
  runOnRecordSegmentComplete: curl -s -X POST -H "X-Internal-Token: ${INTERNAL_TOKEN}" -H "Content-Type: application/json" -d '{"path":"$MTX_PATH","segmentPath":"$MTX_SEGMENT_PATH"}' http://backend:8080/api/internal/mediamtx/segment-complete
paths:
  ~^live/\d+$: {}
```
- 경로는 **`live/<channelId>`** 하나로 통일. 스트림 키는 경로에 넣지 않는다(시청 URL 노출 방지).
- 인증 콜백은 publish만 검사하고 read · playback은 허용(§4.9).

### 2.4 환경변수 (`.env.example`)
```
PUBLIC_HOST=localhost
INTERNAL_TOKEN=change-me
SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/livestream?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
SPRING_DATASOURCE_USERNAME=livestream
SPRING_DATASOURCE_PASSWORD=livestream
JWT_SECRET=32바이트_이상
APP_MEDIAMTX_API_URL=http://mediamtx:9997
APP_MEDIAMTX_INTERNAL_TOKEN=${INTERNAL_TOKEN}
APP_TOSS_CLIENT_KEY=test_gck_...   # 결제위젯 키(gck · gsk). ck · sk(API 개별 연동 키)는 결제창 SDK용이라 위젯에 못 쓴다(§4.13)
APP_TOSS_SECRET_KEY=test_gsk_...
APP_GRID_THRESHOLD=100
APP_SEED_ENABLED=false
APP_RECORDING_RETENTION_DAYS=30
```

## 3. 저장소 구조 (모노레포)
```
livestream/
├─ backend/        Spring Boot (현재 루트의 Gradle 프로젝트를 이동)
├─ frontend/       React + Vite + TypeScript
├─ infra/          compose.yaml · compose.dev.yaml · Caddyfile · mediamtx.yml · tracker/ · seed/sample.mp4
├─ docs/DESIGN.md  이 문서
├─ .env.example
└─ README.md       (DOC-2)
```

## 4. 백엔드

### 4.1 설정값 (`application.yaml` `app.*`, 환경변수 `APP_…`로 덮어씀)
| 키 | 기본 | 뜻 |
|---|---|---|
| `app.grid.threshold` | 100 | 이 동시 시청자 수부터 `recommendedMode=grid` |
| `app.broadcast.disconnect-grace-seconds` | 60 | 송출 끊김 유예 |
| `app.broadcast.end-block-seconds` | 30 | 소유자 종료 · 유예 만료 뒤 같은 채널의 publish를 거절하는 시간(자동 재연결로 새 방송이 생기는 것 방지) |
| `app.jwt.refresh-rotation-grace-seconds` | 10 | 회전된 refresh 토큰을 잠깐 더 받아 주는 시간(탭 여러 개 동시 갱신) |
| `app.broadcast.viewer-broadcast-interval-seconds` | 5 | 시청자 수 이벤트 주기 |
| `app.thumbnail.interval-seconds` | 30 | 라이브 썸네일 캡처 주기 |
| `app.recording.retention-days` | 30 | 영상 파일 보관 |
| `app.recording.dir` | `/recordings` | MediaMTX와 공유 볼륨(`raw/` 조각, `vod/` 공개 영상 — Caddy 정적, `private/` 비공개 영상 — Spring만 읽음). 세 폴더가 같은 볼륨이라 이동은 rename 한 번이다 |
| `app.uploads.dir` | `/uploads` | 썸네일(`thumbnails/`) |
| `app.mediamtx.api-url` / `app.mediamtx.rtsp-url` / `app.mediamtx.internal-token` | — / `rtsp://mediamtx:8554` / — | |
| `app.toss.client-key` / `app.toss.secret-key` | — | 테스트 키 |
| `app.jwt.secret` / `app.jwt.access-minutes` / `app.jwt.refresh-days` | — / 15 / 14 | 기존 `custom.jwt.secret`을 `app.jwt.secret`으로 옮긴다 |
| `app.seed.enabled` | false | 시드 투입 |
| `app.auth.login-max-failures` / `app.auth.signup-max-attempts` / `app.auth.attempt-window-seconds` | 10 / 10 / 300 | 로그인 · 가입 시도 횟수 제한(§4.7). 로그인은 IP+이메일당 실패 횟수, 가입은 IP당 요청 횟수를 고정 창으로 센다 |
| `server.forward-headers-strategy` | `native` | Caddy 뒤에서 클라이언트 IP를 `X-Forwarded-For`로 받는다(§4.7 시도 횟수 제한). Tomcat `RemoteIpValve`의 기본 `internal-proxies`(사설 대역)만 신뢰하므로 compose 네트워크의 Caddy는 신뢰, 그 밖은 무시 |
| `spring.task.scheduling.pool.size` | 4 | 기본 스케줄러가 스레드 1개라 썸네일 캡처(방송당 최대 10초)가 유예 종료 · 보관 정리 스케줄러를 막지 않게 |
| `app.public-url` | `http://localhost` | 안내 문자열(WHIP URL 등)에 사용 |

### 4.2 패키지 구조 (`backend/src/main/java/com/livestream/livestream`)
```
common/      config(SecurityConfig · WebSocketConfig · AsyncConfig · SchedulingConfig · AppProperties · JacksonConfig)
             error(ErrorCode · ApiException · GlobalExceptionHandler · ErrorResponse)
             security(JwtProvider · JwtAuthenticationFilter · CurrentMember · InternalTokenFilter)
auth/        AuthController · AuthService · RefreshToken · RefreshTokenRepository · dto
member/      Member · MemberRepository · MemberController(/members/me)
channel/     Channel · Category · ChannelRepository · ChannelService · ChannelController
broadcast/   Broadcast · BroadcastStatus · BroadcastSource · BroadcastRepository · BroadcastService · BroadcastController
             BroadcastLifecycleService(상태 전이) · DisconnectGraceScheduler · RecommendationService · PublishBlockRegistry(종료 뒤 publish 차단, 메모리)
mediamtx/    MediaMtxAuthController(/internal/mediamtx/auth) · MediaMtxHookController(/ready, /not-ready, /segment-complete)
             MediaMtxClient(Control API: path 조회 · kick)
recording/   Recording · RecordingStatus · Visibility · RecordingSegment · RecordingRepository · RecordingSegmentRepository
             RecordingService · RecordingMergeService(ffmpeg) · RecordingRetentionScheduler · RecordingController · VideoTokenService
thumbnail/   ThumbnailCaptureScheduler · FfmpegRunner(공용)
chat/        ChatController(@MessageMapping) · ChatBufferService · ViewerCounter · BroadcastEventPublisher · StompAuthInterceptor · dto
comment/     Comment · CommentRepository · CommentService · CommentController
post/        Post · PostRepository · PostService · PostController
donation/    Donation · DonationStatus · DonationRepository · DonationService · TossPaymentsClient · DonationController
integration/ WidgetEventController(SSE) · WidgetEventService
seed/        SeedRunner
```
계층: Controller(요청 검증 · DTO) → Service(규칙 · 트랜잭션 · 권한) → Repository(JPA). 엔티티는 Lombok `@Getter`, 생성자는 정적 팩토리, setter 없음.

### 4.3 테이블 · 엔티티
공통 컬럼: `id BIGINT AUTO_INCREMENT PK`, `created_at DATETIME(6)`, `updated_at DATETIME(6)` (`@CreatedDate`/`@LastModifiedDate`, JPA Auditing). 문자열은 `VARCHAR`, enum은 `@Enumerated(STRING)`. 유니크 · check는 `@Table(uniqueConstraints)` · `@Check`로. 단, MySQL은 `ON DELETE CASCADE` FK 컬럼이 들어간 CHECK를 거부하므로(ERROR 3823) 그런 규칙은 엔티티 생성 메서드로 지킨다(comment 참고).

| 테이블 | 컬럼 | 제약 |
|---|---|---|
| `member` | `email VARCHAR(255) COLLATE utf8mb4_bin`, `password_hash VARCHAR(100)`, `nickname VARCHAR(20)` | unique(email), unique(nickname). 비밀번호 BCrypt. **email은 `utf8mb4_bin`**(`@Column(columnDefinition = "VARCHAR(255) COLLATE utf8mb4_bin")`, AUTH-1#4): MySQL 8 기본 `utf8mb4_0900_ai_ci`는 악센트 · 전각 · ß/ss를 같게 보아 `findByEmail`이 다른 글자의 이메일을 같은 회원으로 잡고 로그인 제한 키(§4.7)와 어긋난다. 가입 · 로그인이 `strip` + 소문자로 정규화하므로 bin이어도 대소문자 문제는 없다. `ddl-auto=update`는 기존 컬럼의 콜레이션을 바꾸지 않으므로 이미 만들어진 로컬 DB는 `ALTER TABLE member MODIFY email VARCHAR(255) COLLATE utf8mb4_bin NOT NULL`을 손으로 하거나 볼륨을 지운다(운영 DB는 OPS-5 전이라 없음) |
| `refresh_token` | `member_id FK`, `token_hash CHAR(64)`, `expires_at`, `revoked_at NULL`, `replaced_by_id BIGINT NULL` | **unique(token_hash)**(AUTH-1에서 index → unique로 바꿈: 해시 조회 시 gap lock 데드락 방지). 토큰 원문은 쿠키에만, DB는 SHA-256. `replaced_by_id`는 회전으로 revoke됐을 때 새 토큰 id(로그아웃 revoke는 null) |
| `channel` | `member_id FK`, `name VARCHAR(30)`, `description VARCHAR(500)`, `stream_key CHAR(32)`, `widget_key CHAR(32)`, `auto_publish_recording BOOLEAN default true`, `title VARCHAR(100)`, `category VARCHAR(20)` | unique(member_id), unique(stream_key), unique(widget_key). 가입 시 자동 생성(name = nickname, title = "{nickname}의 방송", category = ETC) |
| `broadcast` | `channel_id FK`, `title`, `category`, `thumbnail_path VARCHAR(255) NULL`, `status VARCHAR(20)`, `source VARCHAR(20)`, `started_at`, `disconnected_at NULL`, `ended_at NULL`, `deleted_at NULL` | index(channel_id, status), index(status, started_at). soft delete: 모든 조회는 `deleted_at IS NULL` |
| `recording` | `broadcast_id FK`, `status VARCHAR(20)`, `file_path VARCHAR(255) NULL`, `duration_seconds INT NULL`, `size_bytes BIGINT NULL`, `visibility VARCHAR(10)`, `file_deleted_at NULL`, `failure_reason VARCHAR(255) NULL` | unique(broadcast_id). file_path는 `app.recording.dir` 기준 상대 경로로 **지금 파일이 있는 곳**을 가리킨다: PUBLIC이면 `vod/<uuid>.mp4`, PRIVATE면 `private/<uuid>.mp4`(§4.10) |
| `recording_segment` | `recording_id FK`, `file_path VARCHAR(255)`, `sequence INT` | 조각 도착 순서 |
| `post` | `channel_id FK`, `title VARCHAR(100)`, `content TEXT` | |
| `comment` | `member_id FK`, `recording_id FK NULL`, `post_id FK NULL`, `parent_id FK NULL(self)`, `content VARCHAR(500)`, `deleted_at NULL` | 대상 하나 규칙 `(recording_id IS NULL) <> (post_id IS NULL)`은 **DB CHECK가 아니라 `Comment.onRecording` · `Comment.onPost` 생성 메서드로** 지킨다(생성자는 private; cascade FK 컬럼은 MySQL CHECK에 못 씀 — `da44053`). FK on delete cascade(recording · post). index(recording_id), index(post_id) |
| `donation` | `broadcast_id FK`, `channel_id FK`, `donor_member_id FK`, `amount INT`, `message VARCHAR(100) NULL`, `order_id CHAR(36)`, `payment_key VARCHAR(200) NULL`, `status VARCHAR(20)`, `approved_at NULL` | unique(order_id), unique(payment_key). index(broadcast_id, status), index(channel_id, status) |

Enum:
- `Category { GAME("게임"), TALK("토크"), MUSIC("음악"), STUDY("공부"), ETC("기타") }` — 응답에는 `code`와 `label` 둘 다.
- `BroadcastStatus { LIVE, DISCONNECTED, ENDED }`, `BroadcastSource { BROWSER_PC, BROWSER_MOBILE, STREAM_KEY }`
- `RecordingStatus { RECORDING, PROCESSING, READY, FAILED, FILE_DELETED }`, `Visibility { PUBLIC, PRIVATE }`
- `DonationStatus { PENDING, APPROVED, FAILED }`

### 4.4 공통 응답 · 에러
- 성공: 본문 그대로(래퍼 없음). 목록은 `{ "items": [...], "page": 0, "size": 20, "totalElements": n, "hasNext": bool }`.
- 에러: `{ "code": "BROADCAST_NOT_LIVE", "message": "방송 중이 아닙니다" }` + HTTP 상태. `ErrorCode` enum에 코드 · 상태 · 메시지.

| 코드 | 상태 |
|---|---|
| `VALIDATION_ERROR` | 400 |
| `UNAUTHENTICATED` / `TOKEN_EXPIRED` / `TOKEN_INVALID` / `LOGIN_FAILED`(이메일 · 비밀번호 불일치, 어느 쪽인지 알려 주지 않음) | 401 |
| `FORBIDDEN` (소유자 · 작성자 아님) | 403 |
| `MEMBER_NOT_FOUND` · `CHANNEL_NOT_FOUND` · `BROADCAST_NOT_FOUND` · `RECORDING_NOT_FOUND` · `POST_NOT_FOUND` · `COMMENT_NOT_FOUND` · `DONATION_NOT_FOUND` | 404 |
| `EMAIL_DUPLICATED` · `NICKNAME_DUPLICATED` | 409 |
| `BROADCAST_NOT_LIVE`(채팅 · 후원 · category 수정이 LIVE/DISCONNECTED가 아닐 때) · `BROADCAST_ALREADY_ENDED`(end API를 ENDED에 부를 때) · `RECORDING_NOT_READY` · `COMMENT_DEPTH_EXCEEDED` · `RECORDING_COMMENT_NOT_OPEN` | 409 |
| `DONATION_AMOUNT_MISMATCH` · `DONATION_ALREADY_PROCESSED` · `PAYMENT_CONFIRM_FAILED` | 400 / 409 / 502 |
| `TOO_MANY_REQUESTS`(로그인 · 가입 시도 횟수 초과, §4.7. 응답에 `Retry-After: <창이 끝날 때까지 남은 초>` 헤더) | 429 |

### 4.5 날짜 · 시각
DB · API 모두 UTC ISO-8601(`2026-11-09T02:11:00Z`). 표시 변환은 프론트.

### 4.6 방송 상태 전이 (`BroadcastLifecycleService`)
```
(없음) ──ready 훅──▶ LIVE ──not-ready 훅──▶ DISCONNECTED ──ready 훅(유예 안)──▶ LIVE
                      │                           │
                      │ end API / delete API      │ 유예(60초) 경과(스케줄러 10초마다)
                      ▼                           ▼
                    ENDED ◀────────────────────────┘
```
| 입력 | 처리 |
|---|---|
| `POST /internal/mediamtx/ready` `{path:"live/<channelId>", sourceType, query}` | 채널 조회. source 판정 = `sourceType`이 `rtmpConn`이면 STREAM_KEY, `webRTCSession`이면 query `src`가 `mobile`→BROWSER_MOBILE, `obs`→STREAM_KEY, 그 외(`pc` · 없음)→BROWSER_PC. 채널에 `status IN (LIVE, DISCONNECTED)` 방송이 있으면 → LIVE로 되돌리고 `disconnected_at=null`, **`source`를 이번 판정값으로 갱신**(유예 재연결 · overridePublisher 교체로 수단이 바뀔 수 있음). 없으면 → 새 `broadcast`(status LIVE, title · category 채널에서 복사, source) + `recording`(RECORDING, visibility는 채널 `auto_publish_recording`에 따라 PUBLIC/PRIVATE). `BroadcastEventPublisher.status(live)`. **늦게 온 훅 거르기:** 진행 중 방송이 없고 `PublishBlockRegistry`가 차단 중이면 새 방송을 만들지 않고 무시(종료 직전 들어온 ready) |
| `POST /internal/mediamtx/not-ready` | 먼저 MediaMTX `GET /v3/paths/get/live/<channelId>`로 **송출자가 아직 붙어 있으면 무시**(훅 순서가 뒤바뀌어 송출 중 방송이 끊긴 것으로 처리되는 것 방지). 방송이 LIVE면 DISCONNECTED + `disconnected_at=now`, `status(disconnected)` 이벤트. 그 외 무시(멱등) |
| `DisconnectGraceScheduler`(10초마다) | `DISCONNECTED AND disconnected_at < now - grace` → `end(broadcast, reason=GRACE_TIMEOUT)` |
| `POST /api/broadcasts/{id}/end` (소유자) → **204** | `end(broadcast, reason=OWNER)`: ENDED면 409 `BROADCAST_ALREADY_ENDED`. ENDED + `ended_at` 저장 **후** `MediaMtxClient.kickPublisher("live/<channelId>")`(LIVE였을 때만). kick 실패는 로그만(이미 ENDED라 이후 훅은 무시) |
| `DELETE /api/broadcasts/{id}` (소유자) → 204 | **잠금 순서 channel → broadcast → recording**(`PESSIMISTIC_WRITE`로 recording을 읽는다 — 일반 SELECT는 RR 스냅숏이라 병합이 막 커밋한 READY · file_path를 FILE_DELETED 갱신이 덮어써 고아 vod가 남는다). LIVE/DISCONNECTED면 먼저 `end`. `deleted_at=now`. recording `FILE_DELETED`로 바꾼 **뒤** 녹화 파일(vod · raw · 썸네일 — `thumbnail_path`가 아직 null이어도 `thumbnails/<id>.jpg`는 지운다) 삭제. **그 recording의 댓글은 물리 삭제**(`deleteByRecordingId`, soft delete라 cascade가 안 일어나므로). 후원은 그대로. 이후 `GET /recordings/{id}` · 댓글 엔드포인트는 broadcast가 삭제된 recording에 404 |
| `end` 공통 | 트랜잭션 안: ENDED + `ended_at`, recording을 `PROCESSING`으로. **커밋 뒤** 순서대로: ① **publish 차단** `PublishBlockRegistry.block(channelId, end-block-seconds)`(메모리 `Map<channelId, Instant>`) ② kick(LIVE였을 때) ③ `status(ended)` 이벤트 ④ `BroadcastEndedEvent(broadcastId, channelId, recordingId)` 발행(`ApplicationEventPublisher`). REC-1의 병합 enqueue(§4.10)와 CHAT-1의 버퍼 정리(§4.14)는 이 이벤트의 `@EventListener`로 붙는다(BC-1은 이벤트만 발행). 인증 콜백(§4.9)이 이 시간 동안 그 채널의 publish를 401로 거절해, kick당한 OBS · WhipClient의 자동 재연결이 새 방송을 만들지 못하게 한다. 차단이 끝난 뒤에도 OBS가 계속 재연결하면 **새 방송이 시작되는 것이 의도된 동작**(README · S5 안내: "사이트에서 종료한 뒤 OBS도 중지하세요") |

- `MediaMtxClient.kickPublisher(path)`: `GET /v3/paths/get/{path}` → `source.type` · `source.id` → `POST /v3/webrtcsessions/kick/{id}` 또는 `/v3/rtmpconns/kick/{id}` (`RestClient`, 타임아웃 3초).
- 채널당 LIVE/DISCONNECTED 방송 최대 1건은 서비스 로직 + `overridePublisher`로 보장(두 번째 연결은 MediaMTX가 기존을 끊고 ready 훅이 다시 와 같은 방송을 이어 간다).
- 송출 중 수정 `PATCH /api/broadcasts/{id}` `{title?, category?}`: LIVE/DISCONNECTED면 방송과 **채널 방송 정보 둘 다** 갱신, ENDED면 `title`만(category가 오면 409 `BROADCAST_NOT_LIVE`).

### 4.7 인증 (`auth`, `common/security`)
- 가입 `POST /api/auth/signup` `{email, password(8~64자, UTF-8 72바이트 이하 — BCrypt 한계), nickname(2~20)}` → 201 `{memberId}`. 채널 자동 생성(§4.3). 중복 409.
- 로그인 `POST /api/auth/login` `{email, password}` → 200 `{accessToken, member:{id, nickname, channelId}}` + `Set-Cookie: refresh_token=<원문>; HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=14d`.
- 갱신 `POST /api/auth/refresh`(쿠키) → 토큰 해시 조회 → 만료면 401. `revoked_at IS NULL`이면 **회전**(기존 revoke + `replaced_by_id`, 새 refresh 발급 · 쿠키 교체) + 응답은 로그인과 같은 `{accessToken, member}`. 이미 revoke됐어도 `replaced_by_id != null AND revoked_at > now - refresh-rotation-grace-seconds`면 **유예**로 보고 다시 회전해 준다(탭 두 개가 동시에 갱신해도 둘 다 살아남음). 유예 밖의 회전된 토큰이 오면 재사용 의심 → 그 회원의 refresh 토큰 **전부** revoke 후 401. 그 외 401 `TOKEN_INVALID` + 쿠키 삭제.
- 로그아웃 `POST /api/auth/logout`(쿠키) → 그 회원의 refresh 토큰 **전부** revoke(`replaced_by_id` null) + 쿠키 삭제, 200. 다른 탭 · 기기도 다음 갱신부터 로그아웃된다. (access는 만료까지 유효 — 15분)
- Access JWT(jjwt): `sub=memberId`, `nickname`, `channelId`, 만료 15분, HS256 `app.jwt.secret`. 헤더 `Authorization: Bearer`. `JwtAuthenticationFilter`가 `SecurityContext`에 `MemberPrincipal(id, nickname, channelId)` 설정. 컨트롤러는 `@CurrentMember MemberPrincipal`(커스텀 리졸버, 없으면 null).
- **만료 · 잘못된 access 처리(AUTH-1#3 결정):** `Authorization` 헤더가 **있는데** 토큰이 만료 · 위조면 공개 경로라도 **401 `TOKEN_EXPIRED` / `TOKEN_INVALID`** 로 응답한다(필터에서 바로 `ErrorResponse` JSON). 헤더가 없으면 익명으로 통과. 이유: 프론트는 로그인 상태일 때만 헤더를 붙이므로, 만료를 조용히 익명 처리하면 `isOwner=false` 같은 잘못된 응답을 refresh 없이 쓰게 된다. 401을 주면 `apiFetch`의 "refresh 1회 후 재시도"가 그대로 동작한다. **예외: `/api/auth/**`는 필터가 검사하지 않는다** — 로그아웃 · 로그인 · refresh는 만료된 access가 헤더에 남아 있어도 처리돼야 한다(`apiFetch`가 `/auth/` 경로에서는 refresh 재시도를 하지 않으므로, 여기서 401을 주면 refresh 쿠키가 폐기되지 않은 채 로그아웃이 실패한다). BC-1(334dd03)에서 구현됨.
- **시도 횟수 제한(AUTH-1#1 결정):** `auth/AuthRateLimiter` — `ChatRateLimiter`와 같은 고정 창 · 인메모리 · 서버 1대 가정, 지난 창은 스케줄러가 지운다. 한도는 §4.1 `app.auth.*`.
  - 로그인: 키 = **클라이언트 IP + 정규화한 이메일**(`AuthService`의 `strip` + 소문자와 같은 규칙. 구현은 NFKD · 악센트 제거까지 접는데, `member.email`이 `utf8mb4_bin`이라(§4.3) DB 비교가 제한 키보다 엄격하므로 글자를 바꾼 이메일로 제한을 피하면서 같은 계정을 맞히는 경로는 없다). 창(300초) 안 **실패**가 `login-max-failures`(10)에 이르면 그 다음부터 429 `TOO_MANY_REQUESTS`. 검사는 DB 조회 · BCrypt 비교 **전에** 하고, 실패(`LOGIN_FAILED`)일 때만 세며, 성공하면 그 키의 카운터를 지운다. 이메일만 키로 쓰면 남의 계정을 잠글 수 있어 IP를 함께 쓴다(한 IP가 이메일을 바꿔 가며 찌르는 것은 막지 못한다 — 받아들인 한계).
  - 가입: 키 = **클라이언트 IP**. 창 안 요청이 `signup-max-attempts`(10)에 이르면 429. 성공 · 실패를 가리지 않고 요청마다 센다(검증 전에 검사).
  - 클라이언트 IP: `HttpServletRequest.getRemoteAddr()`. Caddy의 `reverse_proxy`는 `trusted_proxies`가 없으면 들어온 `X-Forwarded-*`를 버리고 실제 접속 IP만 `X-Forwarded-For`에 넣으며, Spring은 `server.forward-headers-strategy=native`로 그 값을 `getRemoteAddr()`에 반영한다(§4.1). backend 컨테이너는 compose에서 포트를 공개하지 않아 Caddy 말고는 닿지 않는다.
  - 응답 형식은 §4.4 그대로(`message`: "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"). 프론트는 분기 없이 `message`를 보여 준다. refresh · logout에는 제한을 두지 않는다(쿠키 기반이라 무차별 대입 대상이 아님).
- Security: 세션 STATELESS, CSRF 비활성(쿠키는 `/api/auth` 경로 전용 + SameSite=Strict로 방어), `permitAll` 목록은 §4.8의 "누구나" 행. 그 외 `/api/**`는 인증 필요. `/api/internal/**`는 `InternalTokenFilter`(`X-Internal-Token` 비교)만.
- `/swagger-ui/**`, `/v3/api-docs/**` 공개.

### 4.8 권한 규칙 (서비스 계층에서 명시 검증 → `FORBIDDEN` 403)
| 자원 · 행위 | 허용 |
|---|---|
| 목록 · 상세 · 시청 URL · 공개 다시보기 · 댓글 읽기 · 게시글 읽기 · 채팅 읽기(구독) · 채널 공개 정보 | 누구나 |
| `GET/PATCH /channels/me`, 키 재발급 | 로그인(자기 채널) |
| 방송 수정 · 종료 · 삭제 | `broadcast.channel.member_id == me` |
| 송출(MediaMTX publish) | 스트림 키 일치(§4.9) |
| 다시보기 공개 설정, 비공개 조회 · 재생 · video-token | `recording.broadcast.channel.member_id == me` |
| 게시글 작성 · 수정 · 삭제 | `post.channel.member_id == me` |
| 댓글 작성 | 로그인. 다시보기 댓글은 recording READY/FILE_DELETED(=방송 ENDED)이고 PUBLIC(PRIVATE는 주인만) |
| 댓글 수정 · 삭제 | `comment.member_id == me`만(채널 주인도 남의 댓글 불가) |
| 채팅 쓰기, 후원 생성 · 승인 | 로그인 + 방송 LIVE/DISCONNECTED |
검증 헬퍼: `OwnershipGuard.requireChannelOwner(channel, me)`, `requireAuthor(comment, me)`. 테스트 `OwnerAuthorizationTest`: 다른 회원 토큰으로 수정 · 종료 · 삭제 · 공개 설정 · 게시글 · 댓글 → 403.

### 4.9 MediaMTX 인증 콜백 `POST /api/internal/mediamtx/auth`
요청(MediaMTX가 보냄): `{ "ip", "user", "password", "path", "protocol", "id", "action", "query" }`.
- `action == "publish"`: `path`가 `live/<channelId>` 형식이고 채널 존재, `PublishBlockRegistry`에 차단 중이 아니고(§4.6, 차단 중이면 401), 키는 **`password` → `token` → `query`의 `pass`** 순서로 찾아 `channel.stream_key`와 비교(확인됨 2026-11-10, BC-1 카드 "검증": MediaMTX v1.21.3은 WHIP `Authorization: Bearer`를 `token` 칸으로, RTMP `?user=obs&pass=`를 `password` 칸으로 보낸다 — ffmpeg 9.0.2의 WHIP muxer · RTMP로 확인, 실제 OBS 앱 · 브라우저는 FE-2 · OPS-5에서 확인) → 200. 아니면 401.
- **query 문자 제한:** `query`에 `[A-Za-z0-9._~=&%+-]` 밖의 문자가 있으면 키가 맞아도 401. `runOnReady`가 `$MTX_QUERY`를 셸 작은따옴표 · JSON 문자열 안에 그대로 넣으므로, 따옴표 등이 들어오면 셸 명령이 깨지거나 `"path"` 키를 덧붙여 다른 채널의 ready 훅을 흉내 낼 수 있다(가입만 하면 누구나 스트림 키를 받으므로 키 검사만으로는 못 막음).
- `action in (read, playback)`: 200.
- 그 외(api 등): 404(`authHTTPExclude`로 오지 않음).
- 이 엔드포인트는 `InternalTokenFilter` 대상이 아니다(MediaMTX가 헤더를 못 붙임) — 대신 docker 내부망에서만 접근(Caddy가 `/api/internal/*`를 외부에 **노출하지 않는다**: Caddyfile에서 `/api/internal/*` → 403).

### 4.10 녹화 → 다시보기 (`recording`)
- 조각 수집: `POST /internal/mediamtx/segment-complete` `{path, segmentPath}` → 채널의 현재(LIVE/DISCONNECTED/ENDED 직후) 방송의 recording에 `recording_segment` 추가(sequence = count+1). 방송을 못 찾으면 로그만.
- 병합은 `BroadcastEndedEvent`(§4.6)의 `@EventListener`가 `RecordingMergeService.enqueue(event.recordingId())`를 부르며 시작(`@Async`, 단일 스레드 풀): 시작 시 recording을 다시 읽어 **status가 PROCESSING이 아니면(삭제로 FILE_DELETED 등) 아무것도 하지 않고 끝낸다** — 진행 중 방송을 삭제하면 `BroadcastEndedEvent`가 recording이 이미 FILE_DELETED인 채로 발행되므로 이 검사는 필수. recording을 잠글 때는 삭제와 같은 **channel → broadcast → recording** 순서(데드락 방지). 조각이 0개면 FAILED(`NO_SEGMENTS`) → `ffmpeg -f concat -safe 0 -i list.txt -c copy -movflags +faststart <dir>/private/<uuid>.mp4`(**항상 `private/`에 먼저 쓴다** — READY가 되기 전에는 어떤 주소로도 노출되지 않게) → 성공: 저장 직전 새 트랜잭션에서 recording을 잠그고 status를 다시 확인해 PROCESSING일 때만 READY(duration은 `ffprobe`, 파일 크기)로 바꾸고, 그때의 `visibility`가 PUBLIC이면 파일을 `vod/<uuid>.mp4`로 옮긴 뒤(`Files.move` `ATOMIC_MOVE`, 같은 볼륨이라 rename) `file_path`에 옮긴 뒤 경로를 적는다. raw 조각 삭제; 그 사이 FILE_DELETED가 됐으면 만든 파일을 지운다. 실패: FAILED + `failure_reason`(앞 200자). 타임아웃 10분.
- 재시도: `POST /api/recordings/{id}/retry-merge`(소유자, FAILED일 때) — 선택 구현.
- 보관 `RecordingRetentionScheduler` 매일 04:00: `READY AND created_at < now - retention` → vod 파일 삭제, `FILE_DELETED`, `file_deleted_at`.
- 공개 설정 변경 `PATCH /recordings/{id} {visibility}`(주인): recording을 `PESSIMISTIC_WRITE`로 잠그고(§4.6 잠금 순서의 마지막 자리라 단독 잠금이어도 순서와 어긋나지 않는다) ① status가 READY면 파일을 반대 폴더로 옮기고(`vod/` ↔ `private/`, 같은 파일명, `ATOMIC_MOVE`) `file_path`를 옮긴 뒤 경로로, `visibility`를 새 값으로 바꾼다 — 이동이 실패하면 예외로 트랜잭션을 되돌린다(500 `FILE_MOVE_FAILED`) ② READY가 아니면(RECORDING · PROCESSING · FAILED · FILE_DELETED) `visibility`만 바꾼다(PROCESSING은 병합이 READY로 바꿀 때 그 값으로 폴더를 고른다). 같은 값으로 바꾸는 요청은 아무것도 하지 않고 200. 이렇게 해야 공개였다가 비공개로 바꾼 영상이 예전 `/vod/<uuid>.mp4` 주소로 열리지 않는다(REC-2#1) — Caddy는 `vod/`만 내주므로 옮긴 순간부터 그 주소는 404다.
- 파일을 찾는 모든 곳(`RecordingFiles`: 재생 GET · 삭제 · 보관 정리)은 `file_path`를 그대로 쓴다. 이동 뒤 DB 커밋만 실패하는 드문 경우를 위해, `file_path`에 파일이 없으면 다른 vod 폴더(`vod/` ↔ `private/`)의 같은 파일명을 한 번 더 찾고, 찾으면 `file_path`를 그 경로로 고친다.
- 서빙: PUBLIC → `videoUrl = "/" + file_path`(= `/vod/<uuid>.mp4`, Caddy 정적). PRIVATE → `POST /api/recordings/{id}/video-token` → `{token}`(HS256, `sub=recordingId`, 5분) → `videoUrl = "/api/recordings/{id}/video?token=…"`. 이 토큰의 서명 키는 **access 토큰 키와 분리**(`app.jwt.secret`에서 SHA-256으로 파생, `aud=recording-video`) — 같은 키를 쓰면 `sub=recordingId`인 토큰이 같은 id의 회원 access 토큰으로 통과할 수 있다. `GET`은 토큰(aud 포함) 검증 후 `ResourceRegion`으로 Range 응답(`video/mp4`).

### 4.11 썸네일 캡처 (`thumbnail`)
`ThumbnailCaptureScheduler` `interval-seconds`마다 LIVE 방송 각각: `ffmpeg -rtsp_transport tcp -i {rtsp-url}/live/<channelId> -frames:v 1 -vf scale=640:-2 -q:v 4 -y {uploads}/thumbnails/<broadcastId>.tmp.jpg`(타임아웃 10초, 실패는 로그) → 성공하면 **원자적으로 `<broadcastId>.jpg`로 rename**(Caddy가 쓰다 만 파일을 서빙하지 않게). 캡처가 끝난 뒤 방송이 LIVE가 아니거나 삭제됐으면 결과 파일을 버린다. `broadcast.thumbnail_path = "thumbnails/<broadcastId>.jpg"`는 `UPDATE … WHERE thumbnail_path IS NULL`로 처음 한 번만 채운다(파일은 매번 덮어씀). 응답 `thumbnailUrl = "/uploads/" + path`, null이면 프론트가 기본 이미지.

### 4.12 댓글 · 게시글
- 댓글은 한 테이블, 대상은 `recording_id` 또는 `post_id` 중 하나. `parent_id`가 있으면 parent의 `parent_id`가 null이어야 한다(아니면 409 `COMMENT_DEPTH_EXCEEDED`), parent와 같은 대상이어야 한다.
- 삭제는 `deleted_at`(대댓글이 있는 댓글은 "삭제된 댓글입니다"로 남김, 없으면 목록에서 제외).
- 응답 `CommentResponse { id, parentId, content(삭제면 null), deleted, author:{id, nickname, isChannelOwner}, createdAt, updatedAt }` — 평면 목록(createdAt 오름차순), 트리는 프론트.
- 게시글: 채널 주인만 작성 · 수정 · 삭제(물리 삭제, 댓글 cascade). 본문 텍스트(최대 5,000자).

### 4.13 후원 (`donation`, Toss 결제위젯 v2 · 테스트 키)
- **키 종류(DON-1#2 결정):** 결제위젯 v2를 유지하고 키는 **결제위젯 테스트 키 `test_gck_…`(클라이언트) · `test_gsk_…`(시크릿)**를 쓴다. 개발자센터 가입만으로 보이는 API 개별 연동 키(`test_ck_` · `test_sk_`)는 결제창(구 SDK) · 브랜드페이용이라 위젯 SDK가 거부한다. 결제위젯 키는 전자결제 신청 뒤에 발급되므로, 그 전에는 토스 문서가 공개한 결제위젯 테스트 키(`test_gck_docs_…` / `test_gsk_docs_…`, docs.tosspayments.com API 키 문서)를 `.env`에 넣어 쓴다 — 코드 변경 없이 `.env`만 바꾸면 자기 키로 교체된다. 백엔드 confirm은 `Basic base64(secretKey + ':')`라 어느 키든 같다. 문서 공개 키로 한 테스트 결제는 자기 개발자센터 대시보드에 보이지 않는다(받아들임).
1. `POST /api/broadcasts/{id}/donations` `{amount(1000~100000, 1000 단위 아님 허용), message(≤100, 선택)}` → 로그인 · 방송 LIVE/DISCONNECTED 검증 → `donation`(PENDING, `order_id = UUID`, channel_id = 방송 채널) → 201 `{donationId, orderId, amount, clientKey, orderName:"후원", customerName:<닉네임>}`.
2. 프론트 결제위젯 결제 → 성공 콜백 `{paymentKey, orderId, amount}`.
3. `POST /api/donations/confirm` `{paymentKey, orderId, amount}`(로그인, 본인 후원만):
   - `SELECT … FOR UPDATE`로 `order_id` 행 잠금(`@Lock(PESSIMISTIC_WRITE)`).
   - 이미 APPROVED → 200 같은 응답(멱등). FAILED → 409 `DONATION_ALREADY_PROCESSED`.
   - `amount != donation.amount` → 400 `DONATION_AMOUNT_MISMATCH`, 상태 FAILED.
   - `TossPaymentsClient.confirm(paymentKey, orderId, amount)`: `POST https://api.tosspayments.com/v1/payments/confirm`, `Authorization: Basic base64(secretKey + ":")`, 타임아웃 5초. 2xx → APPROVED, `payment_key`, `approved_at`; 아니면 FAILED + 502 `PAYMENT_CONFIRM_FAILED`(Toss 메시지 포함).
   - 승인 후: `BroadcastEventPublisher.donation(...)`(STOMP) + `WidgetEventService.publish(channelId, event)`(SSE) + 채팅 버퍼에 donation 메시지.
4. 조회: `GET /api/broadcasts/{id}/donations?page=` → `{items:[{id, donor:{nickname}, amount, message, approvedAt}], totalAmount, totalCount, …}`(APPROVED만, 공개). `GET /api/members/me/donations/received` → `{totalAmount, byBroadcast:[{broadcastId, title, startedAt, totalAmount, count}]}`.
5. 테스트 `DonationConfirmIdempotencyTest`(Testcontainers): 같은 `{paymentKey, orderId, amount}`로 스레드 2개 동시 confirm(Toss 클라이언트는 mock, 1회 성공) → APPROVED 1건, 합계 1회, Toss 호출 1회.

### 4.14 실시간 (STOMP over WebSocket, `chat`)
- 엔드포인트 `/ws`(네이티브, `setAllowedOriginPatterns("*")` 불필요 — 같은 출처). 브로커 `enableSimpleBroker("/topic", "/queue")`, user 접두어 `/user`, 앱 접두어 `/app`.
- **목적지 제한(보안):** 클라이언트 SEND는 `/app/**`만, SUBSCRIBE는 `/topic/broadcasts/{숫자}`와 `/user/queue/errors`만 허용. 그 외는 ERROR 프레임(그대로 두면 `/topic/broadcasts/{id}`로 직접 SEND해 가짜 chat · donation을 뿌릴 수 있다).
- **전송 실패 전달:** 로그인 사용자의 SEND가 규칙 위반(방송이 LIVE/DISCONNECTED 아님 → `BROADCAST_NOT_LIVE`, 내용 1~200자 위반 → `VALIDATION_ERROR`)이면 연결을 끊지 않고 `@SendToUser("/queue/errors")`로 그 세션에만 `{code, message}`를 보낸다. ERROR 프레임(연결 종료, `message` 헤더 = 코드)은 CONNECT의 `TOKEN_EXPIRED`/`TOKEN_INVALID`와 익명 SEND의 `UNAUTHENTICATED`, 허용되지 않은 목적지에만 쓴다.
- 인증 `StompAuthInterceptor`(CONNECT): `Authorization: Bearer` 헤더가 있으면 검증해 `Principal` 설정 — 만료 · 위조면 CONNECT를 거절(ERROR 프레임 `message: TOKEN_EXPIRED`/`TOKEN_INVALID`), 익명으로 떨어뜨리지 않는다. 헤더가 없으면 익명 허용. SEND는 Principal 필수(없으면 에러 프레임).
- 토픽 `/topic/broadcasts/{id}` 메시지(JSON, `type`으로 구분):
  - `chat` `{type:"chat", id(UUID), member:{id, nickname, isChannelOwner}, content, sentAt}`
  - `donation` `{type:"donation", id, donor:{nickname}, amount, message, approvedAt}`
  - `status` `{type:"status", status:"LIVE"|"DISCONNECTED"|"ENDED", at}`
  - `viewers` `{type:"viewers", count}`
  - `history` `{type:"history", messages:[chat|donation …]}` — 구독 직후 그 세션에만(`convertAndSendToUser` 대신 `SessionSubscribeEvent`에서 세션 id 헤더로 전송)
- 전송 `/app/broadcasts/{id}/chat` `{content(1~200)}` → 방송 LIVE/DISCONNECTED 확인 → 버퍼 추가 → 토픽 발행.
- `ChatBufferService`: 방송별 `ArrayDeque`(최대 50, chat + donation). `BroadcastEndedEvent`(§4.6) `@EventListener`에서 30분 뒤 제거 예약.
- `ViewerCounter`: `SessionSubscribeEvent`(목적지가 `/topic/broadcasts/{id}`) · `SessionUnsubscribeEvent` · `SessionDisconnectEvent`로 방송별 세션 집합. **채널 주인의 구독은 세지 않는다**(구독 시 principal의 memberId가 그 방송 채널의 `member_id`와 같으면 집합에 넣지 않음 — 스튜디오 S3 · S4도 같은 토픽을 구독하므로 그대로 두면 방송인이 항상 1명으로 잡히고, 시연 임계값 2에서는 실제 시청자 1명에 그리드 전환이 난다; 주인의 탭이 여러 개여도 모두 제외). 익명 구독은 센다. `viewer-broadcast-interval-seconds`마다 변화가 있으면 `viewers` 발행. `count(broadcastId)`를 `GET /api/broadcasts/{id}`가 사용.
- 서버 재시작 시 버퍼 · 카운터는 0부터(허용).

### 4.15 공개 연동 SSE (`integration`)
- `GET /api/public/widget/{widgetKey}/events`(인증 없음, `text/event-stream`): 위젯 키로 채널 조회(없으면 404). 연결 시 `event: ready` `data: {"channelId", "version":1}`. 이후 `event: donation`, `id: <donationId>`, `data: {"version":1,"id","type":"donation","broadcastId","donor":{"nickname"},"amount","message","createdAt"}`. 15초마다 `: ping` 주석.
- `Last-Event-ID` 헤더가 오면 그 id 이후의 APPROVED 후원(채널 기준, DB 조회, 최대 100건)을 먼저 보낸다.
- `WidgetEventService`: 채널별 `SseEmitter` 목록, 위젯 키 재발급 시 그 채널 연결 모두 `complete()`.

### 4.16 REST 엔드포인트 요약 (`/api` 접두어)
| 메서드 · 경로 | 인증 | 요청 → 응답 |
|---|---|---|
| `POST /auth/signup` · `/login` · `/refresh` · `/logout` | §4.7 | |
| `GET /members/me` | 로그인 | `{id, email, nickname, channelId, createdAt}` |
| `GET /channels/{id}` | 누구나 | `{id, name, description, category, liveBroadcastId(null 가능), recordingCount, postCount, owner:{nickname}}` |
| `GET /channels/me` | 로그인 | 위 + `{title, streamKey, widgetKey, autoPublishRecording, currentBroadcast:{id,status}, publish:{whipUrl, obsWhipUrl, obsWhipBearer, rtmpUrl, rtmpStreamKey}, widgetUrl}` — `whipUrl = {public-url}/live/{id}/whip`(브라우저가 `?src=pc`/`?src=mobile`을 붙임), `obsWhipUrl = whipUrl + "?src=obs"`, `obsWhipBearer = streamKey`(OBS "Bearer 토큰" 칸에 넣을 값 그대로), `rtmpUrl = rtmp://{host}/live`, `rtmpStreamKey = "{id}?user=obs&pass={streamKey}"`, `widgetUrl = {public-url}/widget/{widgetKey}` |
| `PATCH /channels/me` | 로그인 | `{name?, description?, title?, category?, autoPublishRecording?}` → 채널 |
| `POST /channels/me/stream-key/rotate` | 로그인 | → `{streamKey}`. 라이브 중이면 `end` 후 재발급 |
| `POST /channels/me/widget-key/rotate` | 로그인 | → `{widgetKey, widgetUrl}` |
| `GET /channels/{id}/broadcasts?page=` | 누구나 | ENDED이고 recording READY·PUBLIC인 방송 기록(최신순) |
| `GET /channels/{id}/posts?page=` · `POST /channels/me/posts` | 누구나 / 주인 | `{title, content}` |
| `GET /broadcasts?status=LIVE&category=&page=&size=20` | 누구나 | `status`는 `LIVE`(기본) · `DISCONNECTED`만(ENDED는 400 `VALIDATION_ERROR` — 지난 방송은 `/recordings` · `/channels/{id}/broadcasts`). 시청자 수가 메모리 값이라 진행 중 방송을 모두 읽어 **메모리에서** 정렬 · 페이징: 시청자 수 내림차순 → startedAt 내림차순 → id 내림차순. `BroadcastSummary {id, title, category:{code,label}, thumbnailUrl, status, startedAt, viewerCount, channel:{id, name}}` |
| `GET /broadcasts/{id}` | 누구나 | `BroadcastDetail = Summary + {endedAt, source, viewerCount, recommendedMode:"whep"|"grid", play:{whepUrl:"/live/{channelId}/whep", hlsUrl:"/live/{channelId}/index.m3u8"}, donationTotal, recording:{id, status, visibility}(null 가능), isOwner}` |
| `GET /broadcasts/{id}/recommendations` | 누구나 | 다른 채널 LIVE: 같은 카테고리 → 시청자 수, 최대 4 / 없으면 최근 공개 다시보기 4 → `{lives:[Summary], recordings:[RecordingSummary]}` |
| `PATCH /broadcasts/{id}` · `POST /broadcasts/{id}/end`(204) · `DELETE /broadcasts/{id}`(204) | 주인 | §4.6 |
| `GET /members/me/broadcasts?page=` | 로그인 | 내 방송 기록(삭제 제외) + recording 상태 · visibility |
| `GET /recordings?page=&sort=recent` | 누구나 | READY · PUBLIC → `RecordingSummary {id, broadcastId, title, category, thumbnailUrl, durationSeconds, startedAt, channel:{id,name}, commentCount}` |
| `GET /recordings/{id}` | 누구나/주인 | `{…Summary, status, visibility, videoUrl(READY·PUBLIC일 때), fileDeleted, donationTotal, isOwner}` |
| `PATCH /recordings/{id}` | 주인 | `{visibility}` |
| `POST /recordings/{id}/video-token` · `GET /recordings/{id}/video?token=` | 주인 | §4.10 |
| `GET /recordings/{id}/comments` · `POST …` | 누구나 / 로그인 | §4.12 |
| `GET /posts/{id}` · `PATCH` · `DELETE` · `GET /posts/{id}/comments` · `POST …` | | §4.12 |
| `PATCH /comments/{id}` · `DELETE /comments/{id}` | 작성자 | |
| `POST /broadcasts/{id}/donations` · `POST /donations/confirm` · `GET /broadcasts/{id}/donations` · `GET /members/me/donations/received` | §4.13 | |
| `GET /public/widget/{widgetKey}/events` | 위젯 키 | §4.15 |
| `POST /internal/mediamtx/auth` · `/ready` · `/not-ready` · `/segment-complete` | 내부 | §4.6 · §4.9 · §4.10 |
springdoc: 모든 컨트롤러에 `@Tag`, DTO에 `@Schema(description)`. README의 소유자 검증 시연은 Swagger UI에서 다른 계정 토큰으로 `PATCH /broadcasts/{id}` → 403.

### 4.17 테스트 기준
- 단위: 서비스 규칙(상태 전이, 권한, 댓글 깊이, 후원 금액 검증). 통합(`@SpringBootTest` + Testcontainers): 인증 흐름, 소유자 403(`OwnerAuthorizationTest`), 후원 멱등(`DonationConfirmIdempotencyTest`), 유예 종료 스케줄러. 단위: 댓글 대상 하나 규칙(생성 메서드).
- 외부(MediaMTX · Toss · ffmpeg)는 인터페이스 뒤에 두고 테스트에서 mock. ffmpeg 병합은 `FfmpegRunner`를 가짜로.

## 5. 시드 · 시연 설정 (`seed/SeedRunner`, `app.seed.enabled=true`)
없을 때만 생성: 회원 `demo-streamer@example.com` / `demo-viewer@example.com`(비밀번호는 `.env.example`의 `APP_SEED_PASSWORD`, README에 기재), 채널(방송인 채널 name "데모 방송인", title "데모 방송", GAME), ENDED 방송 1(어제) + recording READY · PUBLIC(`infra/seed/sample.mp4`를 `vod/seed-sample.mp4`로 복사, duration은 ffprobe), 게시글 1("첫 공지"), 댓글 1. 시연 `.env`: `APP_GRID_THRESHOLD=2`, `APP_RECORDING_RETENTION_DAYS=365`.

## 6. 프론트엔드 구조

### 6.1 스택 · 의존성 (`frontend/package.json`)
`react@19`, `react-dom`, `react-router-dom@7`, `@tanstack/react-query@5`, `zustand@5`, `tailwindcss@4`(+ `@tailwindcss/vite`), `@stomp/stompjs@7`, `hls.js@1.6`, `p2p-media-loader-hlsjs@2`, `p2p-media-loader-core@2`, `@tosspayments/tosspayments-sdk@2`, dev: `typescript`, `vite@7`, `vitest`, `@testing-library/react`, `eslint`. UI 컴포넌트 라이브러리 없음, 아이콘은 `lucide-react`.

### 6.2 디렉터리
```
frontend/src/
├─ main.tsx · App.tsx(라우터 · QueryClient · 부트스트랩 refresh)
├─ api/        client.ts(fetch 래퍼 · 401 갱신 1회) · auth.ts · channels.ts · broadcasts.ts · recordings.ts · comments.ts · posts.ts · donations.ts · types.ts(응답 타입 = §4.16)
├─ store/      auth.ts(Zustand: accessToken · member · setAuth · clear)   ※ player.ts(mode · stats)는 두지 않는다 — 모드 · 통계는 Player 안의 로컬 상태(GRID-2). 다른 화면이 모드를 읽어야 할 때 store로 옮긴다
├─ media/      mixer.ts · whip.ts · whep.ts · gridPlayer.ts · devices.ts(기기 감지)
├─ ws/         stomp.ts(연결 · 구독 · 전송, 토큰 헤더)
├─ components/ layout/(Header · Protected) · player/(Player · WhepView · GridView · PlayerStats · ModeToggle) · chat/(ChatPanel · ChatMessage · DonationBanner) · broadcast/(LiveCard · RecordingCard · RecommendList) · comment/(CommentList · CommentForm) · donation/(DonateDialog) · common/(Button · Input · Modal · Spinner · Thumbnail)
├─ pages/      Home · Live · StudioEntry · StudioPc · StudioMobile · StudioSettings · Video · Login · Signup · Me · Channel · Post · Widget
└─ styles/     index.css(Tailwind)
```

### 6.3 라우트 ↔ 화면
| 경로 | 페이지 | 보호 | 핵심 데이터 |
|---|---|---|---|
| `/` | Home(S1) | — | `GET /broadcasts?status=LIVE`, `GET /recordings`, 카테고리 필터(enum 5) |
| `/live/:channelId` | Live(S2) | — | `GET /channels/{id}` → `liveBroadcastId` → `GET /broadcasts/{id}`(5초 refetch), STOMP 구독, Player, ChatPanel, DonateDialog, 종료 화면(추천). `liveBroadcastId`가 null이면 이동하지 않고 **오프라인 화면**: 채널 이름 · 소개, "지금은 방송 중이 아닙니다", 이 채널의 최근 공개 다시보기 4개(`/channels/{id}/broadcasts`), 채널 페이지 링크. 30초마다 다시 조회해 라이브가 시작되면 자동으로 플레이어로 전환 |
| `/studio` | StudioEntry | 로그인 | `devices.ts`로 `/studio/pc` 또는 `/studio/mobile`로 replace. `?force=pc|mobile` 우선 |
| `/studio/pc` | StudioPc(S3) | 로그인 | `GET/PATCH /channels/me`, mixer + whip, 상태, ChatPanel(읽기 + 쓰기), 후원 알림, "모바일 화면으로" |
| `/studio/mobile` | StudioMobile(S4) | 로그인 | 카메라 미리보기(전/후면), 마이크, 방송 정보, 큰 시작/종료, "PC 화면으로" |
| `/studio/settings` | StudioSettings(S5) | 로그인 | 채널 이름 · 소개, 스트림 키(복사 · 재발급 확인), OBS 안내(WHIP/RTMP 문자열 복사), 위젯 URL(복사 · 재발급), 자동 공개 토글 |
| `/videos/:recordingId` | Video(S6) | — | `GET /recordings/{id}`, `<video>`, 댓글, 공개 설정(주인), 보관 만료 안내 |
| `/login` · `/signup` | Login · Signup(S7) | — | `?next=` 복귀 |
| `/me` | Me(S8) | 로그인 | `GET /members/me/broadcasts`(제목 수정 · 삭제 · 공개 설정), `GET /members/me/donations/received`, 로그아웃 |
| `/channel/:channelId` | Channel(S9) | — | 탭 홈(라이브면 Live로 링크 · 소개) · 다시보기(`/channels/{id}/broadcasts`) · 커뮤니티(`/channels/{id}/posts`, 주인이면 글쓰기) |
| `/posts/:postId` | Post(S10) | — | 게시글 · 댓글 |
| `/widget/:widgetKey` | Widget(S11) | — | 레이아웃 없음 · 투명 배경, SSE 구독, 알림 카드 + `speechSynthesis` |

### 6.4 인증 처리 (`api/client.ts`, `store/auth.ts`)
- 앱 시작: `POST /auth/refresh`(쿠키 자동) → 성공 시 `setAuth`, 실패는 조용히 비로그인.
- `ensureFreshToken()`: access의 `exp`(JWT payload base64 디코드)가 **60초 안**이면 먼저 refresh. STOMP 연결 직전과 송출 시작 직전에 부른다(서버가 만료 토큰에 401을 주므로 일반 요청은 `apiFetch`의 재시도로 충분).
- `apiFetch(path, init)`: `Authorization` 헤더 자동, 응답 401(`TOKEN_EXPIRED`/`UNAUTHENTICATED`)이면 refresh 1회 후 재시도, 또 401이면 `clear()`. 한 탭 안에서 동시에 여러 요청이 401을 받아도 refresh 호출은 **하나로 합친다**(진행 중인 refresh Promise 공유).
- `Protected` 래퍼: 비로그인 → `/login?next=<현재>`.
- 로그아웃: `POST /auth/logout` → `clear()` → `/`.

### 6.5 미디어 모듈
`media/devices.ts`
- `isMobileDevice() = typeof navigator.mediaDevices?.getDisplayMedia !== 'function' || /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent)`.

`media/mixer.ts` — `createPcStream({ screen: true, mic: deviceId | null })`
1. `display = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30 }, audio: true })` — 사용자가 탭/화면을 고르고 "탭 소리 공유" 체크 가능. `display.getAudioTracks().length === 0`이면 `warnings.push('NO_SYSTEM_AUDIO')`.
2. `mic = await getUserMedia({ audio: { deviceId, echoCancellation: true, noiseSuppression: true } })`(선택 해제 가능).
3. `AudioContext` → 각 오디오 트랙을 `createMediaStreamSource` → `GainNode`(마이크 · 시스템 각각 볼륨) → `MediaStreamAudioDestinationNode` → 오디오 트랙 1개.
4. 반환 `{ stream: MediaStream(비디오 1 + 오디오 1), warnings, setMicGain, setSystemGain, stop() }`. `display` 비디오 트랙 `ended`(사용자가 공유 중지)면 `onEnded` 콜백 → 스튜디오가 송출 종료.
- 모바일 `createMobileStream({ facing: 'user'|'environment' })` = `getUserMedia({ video: { facingMode, width: 1280, height: 720 }, audio: true })`. 전환은 트랙 교체(`RTCRtpSender.replaceTrack`).

`media/whip.ts` — `class WhipClient { constructor(url, bearer) ; publish(stream) ; close() ; onState(cb) }`
- `RTCPeerConnection({ iceServers: [] })`(같은 서버로 직접, TURN 없음), `addTransceiver(track, { direction: 'sendonly' })`, 비디오 인코딩 `maxBitrate 3_000_000`.
- `createOffer` → `setLocalDescription` → ICE gathering 완료 대기(최대 2초) → `POST url` (`Content-Type: application/sdp`, `Authorization: Bearer <streamKey>`) → 201 + `Location` 저장 → `setRemoteDescription(answer)`.
- `close()`: `closed = true`(이후 재시도 중단) → `DELETE Location` → `pc.close()`. `oniceconnectionstatechange`: `failed`/`disconnected`이고 `!closed`면 `onState('reconnecting')` → 3초 간격 재시도 최대 20회(유예 60초 안) → 성공 시 `'live'`. 재시도 중 401(종료 차단)을 받으면 즉시 중단하고 `'ended'`.
- 송출 URL은 `channels/me.publish.whipUrl + '?src=pc'`(모바일은 `?src=mobile`).

`media/whep.ts` — `class WhepPlayer { attach(video, url) ; close() ; onState(cb) }`: `addTransceiver('video', recvonly)` · `('audio', recvonly)`, offer → `POST url`(헤더 없음) → answer, `ontrack`으로 `video.srcObject`. 끊기면 `'waiting'`; `status: LIVE` 이벤트가 오면 재연결.

`media/gridPlayer.ts` — `createGridPlayer(video, hlsUrl, { trackerUrl, onStats })`: `HlsJsP2PEngine`(p2p-media-loader 2.3.x) + `Hls(GRID_HLS_CONFIG)`, `announceTrackers: [wss://<host>/tracker]`. 통계: `onChunkDownloaded(bytes, source: 'http'|'p2p')`, `onChunkUploaded(bytes)` → 누적 → `onStats({ httpBytes, p2pDownBytes, p2pUpBytes, peers })`. GRID-1 · GRID-2에서 정해진 것(구현 `1125b03` · `2db679b` 기준, 설계보다 우선):
- **`GRID_HLS_CONFIG = { lowLatencyMode: false, liveSyncDuration: 6 }`**, 코어 `GRID_P2P_CORE_CONFIG = { highDemandTimeWindow: 2 }`. p2p-media-loader는 LL-HLS part를 P2P로 받지 않고, 재생 위치에서 highDemand 창(기본 15초) 안의 세그먼트는 곧바로 HTTP로 받는다. 그래서 그리드 모드는 지연(7~8초 예상)을 내주고 세그먼트 단위로 받아 피어 교환 틈을 만든다. 기본 모드(WHEP)는 영향 없음. `liveSyncDuration`을 직접 줘야 엔진이 `liveSyncDurationCount`를 크게 늘리지 않는다. 서버 쪽 LL-HLS 설정(§2.3)은 그대로이며, 같은 스트림을 그리드는 비LL로, 다른 소비자는 LL로 쓸 수 있다.
- hls.js는 `lowLatencyMode`를 꺼도 서버가 `CAN-BLOCK-RELOAD=YES`면 재생목록을 `?_HLS_msn=…&_HLS_part=…`로 다시 받고, 그러면 P2P 엔진이 재생목록을 잃는다. 재생목록 응답 주소에서 `_HLS_msn` · `_HLS_part` · `_HLS_skip`을 빼는 playlist loader를 끼운다.
- **swarm id = 경로 `/live/{channelId}/index.m3u8`**(`swarmIdOf(hlsUrl)`), 전체 URL이 아니다. 접속 호스트(localhost · 운영 도메인 · IP)가 달라도 같은 스웜이 되게 한다.
- P2P 코어는 Vite `resolve.conditions`의 `p2pml:core-as-bundle`(폴리필 포함 번들)로 불러온다. 새 의존성 없음.
- 재측정(기기 3대)에서 절감이 모자라면 재기획: **B** MediaMTX `hlsSegmentCount`를 늘리고(기본 7, 1초 세그먼트라 지금은 sync를 6초보다 키울 수 없음) sync 10초 이상, 또는 **C** §8의 비LL 변형(`hlsVariant` 변경). 둘 다 infra 변경이라 GRID-2 세션이 측정 결과와 함께 지휘 세션 · 설계자에게 올린다.

`components/player/Player.tsx` — props `{ broadcast, initialMode }`. 상태 `mode: 'whep'|'grid'`(초기값 `recommendedMode`), `ModeToggle`로 전환(이전 플레이어 `close()` 후 생성), `PlayerStats`는 grid일 때 "서버 n MB · 피어 m MB · 절감 k% · 피어 p명". `status` 이벤트: `DISCONNECTED` → 오버레이 "방송인 연결이 끊겼습니다. 재연결을 기다리는 중…", `ENDED` → 종료 화면(`EndedView`: 안내 · 다시보기 버튼(recording READY면 활성, 아니면 "준비 중") · `RecommendList`).

### 6.6 STOMP 클라이언트 (`ws/stomp.ts`)
`connect(token?)` → `Client({ brokerURL: wss://<host>/ws, connectHeaders: token ? { Authorization: 'Bearer ' + token } : {} , reconnectDelay: 3000 })`. `subscribeBroadcast(id, handler)` → `/topic/broadcasts/{id}`; `sendChat(id, content)` → `/app/broadcasts/{id}/chat`. `history` 메시지는 목록 초기화에 사용. 로그인 상태면 `/user/queue/errors`도 구독해 `{code, message}`를 채팅 입력창 아래 안내로 표시(연결은 유지됨). 로그인 상태가 바뀌면 재연결. 연결 전 `ensureFreshToken()`. 서버가 만료 토큰의 CONNECT를 ERROR 프레임(`message: TOKEN_EXPIRED`)으로 거절하면 refresh 후 재연결(익명 연결로 떨어지지 않는다).

### 6.7 화면별 요소 (PLAN-1 최종 화면 목록 기준)
- **S1 Home**: 카테고리 칩(전체 + 5), `LiveCard` 그리드(썸네일 · LIVE 배지 · 시청자 수 · 제목 · 채널명), 아래 "다시보기" 탭(`RecordingCard`). 30초 refetch.
- **S2 Live**: 상단 Player(16:9, 모바일은 세로 비율 자동), 제목 · 채널 링크 · 시청자 수 · 카테고리, 우측(모바일은 하단) ChatPanel(비로그인은 입력창 대신 "로그인하고 채팅하기"), 후원 버튼 → `DonateDialog`(금액 1,000/5,000/10,000 + 직접 입력, 메시지 100자) → Toss 결제위젯 → confirm. 후원 발생 시 `DonationBanner`(플레이어 위 5초) + 채팅 강조.
- **S3 StudioPc**: 왼쪽 미리보기(`<video muted>`), 소스 선택(화면 공유 버튼 · 마이크 드롭다운 · 시스템 소리 포함 체크 · 각 볼륨), 경고(`NO_SYSTEM_AUDIO`: "탭/시스템 소리가 포함되지 않았습니다"), 방송 정보 폼(제목 · 카테고리, 송출 중에도 저장 → `PATCH /broadcasts/{id}`), 송출 시작/종료 버튼, 상태 표시(연결 중 · 라이브 · 재연결 중 · 시청자 수), ChatPanel, 후원 알림, `getDisplayMedia` 없으면 안내 + "모바일 화면으로".
- **S4 StudioMobile**: 전체 화면 카메라 미리보기, 상단 방송 정보(제목 · 카테고리), 전/후면 전환 · 마이크 음소거, 하단 큰 시작/종료, 라이브 중 시청자 수. 화면 꺼짐 방지 `navigator.wakeLock`.
- **S5 StudioSettings**, **S6 Video**(댓글은 `recording.status`가 READY/FILE_DELETED일 때만 폼 표시, FILE_DELETED면 "보관 기간(30일)이 지나 영상이 삭제되었습니다"), **S8 Me**, **S9 Channel**, **S10 Post**: §6.3.
- **S11 Widget**: `EventSource(/api/public/widget/{key}/events)`; `donation` 이벤트 → 카드 6초 표시 + `speechSynthesis.speak("{nickname}님이 {amount}원 후원: {message}")`(한국어 음성 선택, 없으면 기본). 큐로 순차 재생. 같은 후원이 두 번 올 수 있으므로(재연결 · `Last-Event-ID` 경계) **후원 `id`로 중복을 거른다**(최근 id 집합 보관).

## 7. 인프라 세부 (OPS-3 · OPS-5)
- `backend/Dockerfile`: 현재 Dockerfile 유지 + 런타임 스테이지에 `RUN apk add --no-cache ffmpeg`.
- `infra/tracker/Dockerfile`: `node:22-alpine`, `npm i -g wt-tracker`, `CMD wt-tracker --port 8000`(설정 파일로 `websocket.path: /tracker`).
- MySQL: `livestream` DB, 볼륨 `mysql_data`. `ddl-auto: update`.
- Caddy: 도메인(`PUBLIC_HOST`)으로 자동 HTTPS. 로컬은 `localhost`(내부 CA).
- 포트 개방(운영): 80, 443/tcp, 1935/tcp(RTMP), 8189/udp(WebRTC).

## 8. 미확인 위험 · PoC (구현 항목에서 먼저 확인)
| 위험 | 어디서 | 안 되면 |
|---|---|---|
| LL-HLS 부분 세그먼트에서 p2p-media-loader가 피어 교환을 하는지 — **확인(GRID-1): part는 P2P로 안 받는다.** 클라이언트만 비LL로(`lowLatencyMode: false`, sync 6초, §6.5) 세그먼트 단위 교환으로 대응 중, 절감률은 GRID-2 재측정 대기 | GRID-1 → GRID-2 | 재측정이 모자라면 B(`hlsSegmentCount` 증가 + sync 10초 이상) 또는 C(`hlsVariant` 비LL 변형)로 그리드 모드만 전환, 기본 모드(WHEP)는 유지 |
| iOS Safari 네이티브 HLS가 Opus 오디오를 재생하는지 | GRID-1 | 그리드 모드는 iOS에서 소리 없음 안내, 기본 모드 사용 |
| ~~MediaMTX가 WHIP `Authorization: Bearer`를 인증 콜백 `password`로 전달하는지~~ **해소(2026-11-10, BC-1)**: v1.21.3은 Bearer를 `token`, RTMP pass를 `password`로 보낸다. ffmpeg 9.0.2로 확인, 실제 OBS · 브라우저는 미확인 | BC-1 | (불필요 — §4.9의 키 탐색 순서가 둘 다 받는다) |
| OBS 브라우저 소스에서 `speechSynthesis` 동작 | FE-9 | 위젯을 일반 Chrome 창으로 열어 OBS 창 캡처 안내 |
| 가정 회선 인바운드 443 차단(맥미니 호스트일 때) | OPS-5 | Lightsail |

## 9. 항목별 구현 지침 (로드맵 ID)

각 항목의 "읽을 절"과 "완료 기준". 의존성 추가는 여기 적힌 것만.

### OPS-2 모노레포 구조
- 읽을 절: §3. 루트의 Gradle 프로젝트 전부(`build.gradle`, `settings.gradle`, `gradle/`, `gradlew*`, `src/`, `Dockerfile`, `.dockerignore`)를 `backend/`로 `git mv`. `frontend/`는 `npm create vite@latest frontend -- --template react-ts` + §6.1 의존성 + Tailwind 설정 + 빈 라우터. `infra/` 빈 디렉터리 + README 한 줄. 루트 `.gitignore` 갱신(`frontend/node_modules`, `frontend/dist`, `.env`).
- 완료: `cd backend && ./gradlew test` 통과, `cd frontend && npm run build` 성공. 커밋 2개(이동 / 프론트 생성).

### OPS-3 로컬 실행 환경
- 읽을 절: §2.1~2.4, §7. `infra/compose.yaml`, `compose.dev.yaml`, `Caddyfile`, `mediamtx.yml`, `tracker/`, `.env.example`. `application.yaml`에 §4.1 `app.*` 전부(기본값) + `AppProperties`(`@ConfigurationProperties("app")`).
- 완료: `docker compose -f infra/compose.dev.yaml up`로 MySQL · MediaMTX · Caddy · tracker가 뜨고, IDE에서 backend 실행 후 `https://localhost/api/...`가 프록시되며, OBS로 `rtmp://localhost/live` + `1?user=obs&pass=x`가 MediaMTX에 닿는다(인증 콜백은 BC-1 전이라 `authMethod: internal`로 잠시 두고 주석으로 표시). `https://localhost/live/<channelId>`를 주소창에 직접 입력하거나 새로고침해도 SPA가 뜬다(`/live/*` 블록의 마지막 handle이 프론트 서빙, §2.2).

### AUTH-1 회원가입 · 로그인 · 갱신 · 로그아웃
- 읽을 절: §4.2 common/auth/member, §4.3 member · refresh_token · channel(자동 생성만, 나머지 채널 로직은 CH-1), §4.4, §4.7, §4.8의 공개 경로. `channel` 엔티티는 여기서 만든다(CH-1이 확장).
- 완료: 가입 → 로그인 → `/members/me` → refresh 회전 → 로그아웃 후 refresh 401 통합 테스트. 중복 409 테스트.
- 추가(AUTH-1#1): `AuthRateLimiter` 단위 테스트(`Clock` 주입 — 10회 실패 뒤 429, 성공 시 초기화, 창이 지나면 다시 허용, 다른 IP · 다른 이메일은 영향 없음) + 로그인 통합 테스트(11번째 실패가 429 · `Retry-After` 있음, 429일 때 BCrypt · DB 조회가 안 일어남은 로그나 mock으로). 가입은 IP당 11번째 요청 429. `X-Forwarded-For`를 사설 IP에서 보낸 요청이 그 값을 키로 쓰는지 테스트 1개(MockMvc `remoteAddr` 127.0.0.1 + 헤더).
- 추가(AUTH-1#4): 통합 테스트(Testcontainers MySQL) — `victim@example.com` 가입 뒤 `vīctim@example.com` · 전각 `ｖictim@example.com` 로그인이 `LOGIN_FAILED`(회원을 못 찾음)이고, 가입도 중복 409가 아니라 새 회원이 된다(콜레이션이 bin임을 동작으로 확인).

### FE-1 프론트 기반
- 읽을 절: §6.1~6.4, S7. `App.tsx` 라우터(모든 경로를 placeholder 페이지로), `api/client.ts`, `store/auth.ts`, `Header`(로그인 상태 · 방송하기 버튼 → `/studio`), `Protected`, Login · Signup 페이지.
- 완료: 가입 · 로그인 · 새로고침 후 로그인 유지(refresh) · 로그아웃이 브라우저에서 동작. vitest로 `client.ts` 401 갱신 1회 테스트.

### GRID-1 그리드 PoC
- 읽을 절: §2.3 HLS 설정, §6.5 gridPlayer, §8. `frontend/poc/grid.html`(독립 페이지)로 OBS → MediaMTX LL-HLS를 hls.js + p2p-media-loader로 브라우저 3개에서 재생, 피어 간 바이트 교환이 통계에 잡히는지, 지연(초)을 측정. iOS Safari에서 소리 확인.
- 완료: 카드에 측정 결과(지연 · 피어 교환 여부 · 사용한 라이브러리 API 이름)와 §8의 대응 결정을 적는다. 설계자에게 결과 보고. 코드는 `frontend/poc/`에 남긴다.

### CH-1 채널 · 방송 정보 · 스트림 키 API
- 읽을 절: §4.3 channel, §4.16 `/channels/*`(broadcasts · posts 목록 제외). 키 생성은 `SecureRandom` 32 hex. `publish` 안내 문자열은 `app.public-url`로.
- 완료: `GET/PATCH /channels/me`, 재발급 2개, `GET /channels/{id}`(liveBroadcastId는 BC-1 전까지 null) 통합 테스트 + 다른 회원 403.

### BC-1 방송 상태 전이 · MediaMTX 연동
- 읽을 절: §4.6, §4.9, §4.3 broadcast · recording(생성만), §2.3, §8 Bearer 위험. `MediaMtxClient`(`RestClient`), 훅 컨트롤러, `InternalTokenFilter`, `DisconnectGraceScheduler`, `BroadcastEventPublisher`(CHAT-1 전에는 인터페이스 + 로그 구현).
- 완료: 통합 테스트 — ready → LIVE 생성, not-ready → DISCONNECTED, 유예 안 ready → 같은 방송 LIVE, 유예 경과 → ENDED, end API → kick 호출(mock) · 다른 회원 403, 인증 콜백 publish 키 일치/불일치. 실제 OBS · 브라우저 WHIP로 수동 확인(Bearer 전달 여부 카드에 기록). `mediamtx.yml`을 `authMethod: http`로 전환.

### BC-2 방송 목록 · 상세 · 수정 · 삭제 · 추천 API
- 읽을 절: §4.6 수정 · 삭제, §4.16 `/broadcasts*`, `/members/me/broadcasts`, `/channels/{id}/broadcasts`, `RecommendationService`. `viewerCount`는 `ViewerCounter` 인터페이스(CHAT-1 전에는 0) · `recommendedMode = count >= threshold ? grid : whep`.
- 완료: 목록 정렬 · 카테고리 필터 · 상세 · 추천 규칙 · PATCH(LIVE면 채널도 갱신, ENDED면 title만) · DELETE(soft, 파일 삭제 호출 mock) 테스트 + 403.

### BC-3 라이브 썸네일 자동 캡처
- 읽을 절: §4.11, `FfmpegRunner`(프로세스 실행 · 타임아웃 공용, REC-1과 공유 — 먼저 끝나는 쪽이 만든다).
- 완료: LIVE 방송에 30초 안에 `thumbnails/<id>.jpg` 생성, 응답 `thumbnailUrl` 반영(수동 확인). 단위 테스트는 `FfmpegRunner` mock으로 경로 갱신 로직.

### FE-2 PC 송출 스튜디오
- 읽을 절: §6.5 mixer · whip, §6.7 S3, §4.16 `/channels/me`. Chrome 기준. 송출 시작 흐름: `PATCH /channels/me`(방송 정보) → `createPcStream` → `WhipClient.publish` → `GET /channels/me` 폴링(3초)으로 `currentBroadcast` 확인 → 라이브 표시. 종료 순서(중요): **`whip.close()` → `stream.stop()` → `POST /broadcasts/{id}/end`** — 먼저 송출을 끊어 재시도가 돌지 않게 한 뒤 서버에 종료를 알린다(서버는 어차피 30초 차단으로 2중 방어).
- 완료: Chrome에서 화면 + 탭 소리 + 마이크 송출이 MediaMTX에 붙고(OBS 없이), 다른 브라우저 WHEP(간단 테스트 페이지 또는 FE-4)로 소리 · 화면 확인. 공유 중지 시 자동 종료.

### FE-3 모바일 송출 · 기기 감지 · 채널 설정
- 송출 시작 · 종료 순서는 FE-2와 같다(`PATCH /channels/me` → `createMobileStream` → `WhipClient.publish(whipUrl + '?src=mobile')` → `/channels/me` 폴링 / 종료는 `whip.close()` → `stream.stop()` → `end`).
- 읽을 절: §6.5 devices · 모바일 스트림, §6.7 S4 · S5, §6.3. iOS Safari · Android Chrome 모두 확인(HTTPS 필수 → `compose.dev` Caddy 내부 CA를 폰에 신뢰시키거나 OPS-5 뒤 운영 도메인).
- 완료: 휴대폰에서 `/studio` → 모바일 화면 자동 → 카메라 송출 → PC에서 시청. 전/후면 전환. S5에서 키 복사 · 재발급 · OBS 문자열 · 자동 공개 토글.

### FE-4 홈 · 시청 화면 기본 모드
- 읽을 절: §6.5 whep · Player(grid 제외), §6.7 S1 · S2(채팅 · 후원 제외), §4.16. 종료 · 유예 오버레이는 `GET /broadcasts/{id}` 5초 폴링으로(STOMP는 FE-5에서 교체).
- 완료: 홈 목록 · 필터, 카드 → 시청 → WHEP 자동 재생, 방송 종료 시 종료 화면 + 추천, 다시보기 버튼(REC-2 전엔 비활성).

### GRID-2 그리드 모드 플레이어 · 자동 전환
- 읽을 절: §6.5 gridPlayer · Player, §2.1 tracker, GRID-1 카드 결과. `ModeToggle`, `PlayerStats`, 초기 모드 = `recommendedMode`.
- 완료: 기기 3대에서 그리드 모드 통계에 피어 바이트가 올라가고, `APP_GRID_THRESHOLD=2`일 때 세 번째 시청자가 그리드로 시작. 토글로 양방향 전환.

### CHAT-1 STOMP 채팅 · 이벤트 · 시청자 수
- 읽을 절: §4.14, §4.6 이벤트 발행 지점. 의존성 `spring-boot-starter-websocket`. `BroadcastEventPublisher` 실구현, `ViewerCounter` 실구현(BC-2 연결).
- 완료: 통합 테스트(STOMP 클라이언트): 익명 구독 · history 수신, 로그인 전송 · 브로드캐스트, 익명 전송 거부, 구독 수 카운트(채널 주인의 구독은 0으로 셈). 버퍼 50 초과 시 오래된 것 제거.

### FE-5 채팅 UI
- 읽을 절: §6.6, §6.7 S2 · S3 ChatPanel. FE-4의 폴링을 STOMP `status` · `viewers`로 교체(폴링은 30초 백업).
- 완료: 두 브라우저 간 실시간 채팅, 입장 시 최근 메시지, 비로그인 입력 차단, 방송인 배지.

### DON-1 후원 API
- 읽을 절: §4.13, §4.3 donation. `TossPaymentsClient` 인터페이스 + `RestClient` 구현. 테스트 `DonationConfirmIdempotencyTest`.
- 완료: 생성 → confirm(mock 성공) → APPROVED · 이벤트 발행 · 목록 · 합계, 금액 불일치 400, 동시 2요청 멱등, 비라이브 409.

### FE-6 후원 UI
- 읽을 절: §6.7 S2 DonateDialog · DonationBanner, S8 받은 후원, §4.13 1~3. Toss 결제위젯 v2 SDK(테스트 클라이언트 키), 결제 성공 URL은 `/live/:channelId?payment=success&paymentKey=&orderId=&amount=`로 돌아와 confirm 호출.
- 완료: 테스트 카드로 결제 → 채팅 강조 + 배너 + 스튜디오 알림, 마이페이지 방송별 합계.

### WID-1 위젯 키 SSE (백엔드)
- 읽을 절: §4.15. README 연동 문서 초안(이벤트 형식 · curl 예시)은 `docs/`가 아니라 README 절로(DOC-2가 합침) — 이 항목에서는 카드에 적어 둔다. 알림 위젯 S11 화면 · TTS는 FE-9(2026-11-11 분리).
- 완료: curl로 SSE 수신(ready → donation, 15초 ping), `Last-Event-ID` 복구, 위젯 키 재발급 시 연결 종료, 없는 키 404 통합 테스트.

### REC-1 녹화 세그먼트 수집 · 병합 · 정리
- 읽을 절: §4.10, §4.3 recording · recording_segment, `FfmpegRunner`, §2.3 record 설정.
- 완료: 실제 송출 → 종료 → 병합 결과가 `private/<uuid>.mp4`에 생기고 READY 시점에 `autoPublishRecording`에 따라 `vod/`로 옮겨지거나 `private/`에 남음(수동), 재연결로 조각 2개일 때 병합(수동), 단위 테스트(mock ffmpeg: 조각 0 → FAILED, 성공 → READY + PUBLIC이면 `vod/`로 이동 · PRIVATE면 `private/` 유지), 보관 스케줄러 테스트(시계 주입, 두 폴더 모두 삭제).

### REC-2 다시보기 API
- 읽을 절: §4.16 `/recordings*`, `/channels/{id}/broadcasts`, §4.10 서빙 · 토큰, §4.8.
- 완료: 공개 목록 · 상세 · 비공개 주인만 · video-token → Range 응답(206) 테스트, 공개 설정 변경 403. **공개 → 비공개 변경 시 파일이 `vod/` → `private/`로 옮겨지고 `file_path`가 바뀌는 테스트**(임시 디렉터리), 되돌릴 때 반대 방향, READY가 아니면 파일 이동 없이 visibility만. 수동: 비공개로 바꾼 뒤 예전 `/vod/<uuid>.mp4`가 404(REC-2#1).

### CMT-1 댓글 API
- 읽을 절: §4.12, §4.3 comment, §4.8. 다시보기 · 게시글 두 엔드포인트 묶음이 같은 서비스.
- 완료: 작성 · 대댓글 · 깊이 초과 409 · 다른 대상 parent 400 · 수정 · 삭제(작성자만, 대댓글 있으면 표시 유지) · 라이브 중 다시보기 댓글 409 · 비공개 다시보기 댓글 403 테스트.

### POST-1 커뮤니티 게시글 API
- 읽을 절: §4.12 게시글, §4.16 `/posts*`, `/channels/{id}/posts`.
- 완료: 주인만 작성 · 수정 · 삭제(403 테스트), 목록 최신순, 삭제 시 댓글 cascade.

### FE-7 다시보기 · 마이페이지 · 채널 페이지
- 읽을 절: §6.3 · §6.7 S6 · S8 · S9(커뮤니티 탭은 FE-8). 비공개 재생은 video-token 흐름.
- 완료: 종료된 방송 → 다시보기 재생(공개 · 비공개 주인), 마이페이지 제목 수정 · 삭제 · 공개 설정 · 받은 후원, 채널 페이지 홈 · 다시보기 탭.

### FE-8 댓글 · 커뮤니티 UI
- 읽을 절: §6.7 S6 댓글 · S9 커뮤니티 탭 · S10. `CommentList`는 평면 목록을 parentId로 2단 트리로.
- 완료: 다시보기 댓글 · 대댓글 · 수정 · 삭제(작성자만 버튼), 게시글 작성(주인) · 댓글.

### OPS-4 시드 데이터 · 시연 설정
- 읽을 절: §5. `infra/seed/sample.mp4`(10초, 저작권 없는 직접 녹화).
- 완료: `APP_SEED_ENABLED=true`로 기동하면 계정 · 다시보기 · 게시글이 보이고, 두 번 기동해도 중복 없음(테스트).

### OPS-5 배포
- 읽을 절: §2, §7, ARCH-1 카드 "맥미니 대체 조사"(호스트는 사용자 결정 대기). 운영 `compose.yaml`로 전체 기동, 도메인 HTTPS, 포트 개방, `webrtcAdditionalHosts`.
- 완료: 외부 기기(LTE)에서 가입 · 송출 · 시청 · 채팅이 동작. 서버 재시작 후 데이터 유지 확인.

### FE-9 알림 위젯 S11 · TTS PoC
- 읽을 절: §6.3 `/widget/:widgetKey`, §6.7 S11, §4.15(이벤트 형식), §8 TTS 위험. WID-1 카드 "API · 스키마 상세"(같은 후원이 두 번 올 수 있는 경우).
- 완료: ① `/widget/<key>`를 열면 레이아웃 없이 투명 배경으로 `EventSource` 구독, `donation` 이벤트에 카드 6초 표시 + `speechSynthesis`(한국어 음성, 큐 순차 재생) ② 같은 후원 `id`가 두 번 와도 한 번만 표시(vitest, 이벤트 핸들러 단위) ③ 끊기면 브라우저 `EventSource` 자동 재연결에 맡기고 `Last-Event-ID`는 브라우저가 보내는 것을 그대로 쓴다(직접 구현 없음) ④ 옛 키(404)는 "위젯 키가 바뀌었습니다" 문구 ⑤ 일반 Chrome 창에서 TTS 동작 확인, **OBS 브라우저 소스에서 TTS 동작 여부를 카드에 기록**(안 되면 §8의 대응: 일반 Chrome 창 + OBS 창 캡처 안내를 S5 위젯 설정 문구로) ⑥ README 연동 절에 넣을 안내 문구(위젯 주소 · OBS 설정 · 안 될 때)를 카드에 적는다(DOC-2가 합침).

### DOC-2 README
- 읽을 절: PLAN-1 카드 "확정 사항 14"(시연 12단계) · §1 체크 대응 · §4.15 연동 문서. 절: 서비스 URL · 테스트 계정 · 로컬 실행 · 운영 배포 · 기능 확인 절차(12단계, 각 단계에 체크 항목 표기) · 소유자 검증 · 멱등 테스트 이름 · 공개 연동 문서 · 아키텍처 요약(이 문서 링크).
- 완료: 처음 보는 사람이 README만으로 로컬 실행 · 시연 순서를 따라갈 수 있다(설계자가 검토).

### OPS-6 최종 리허설
- 완료: 시연 12단계를 운영 URL에서 끝까지 수행, 발견한 문제는 이슈 파일에, 막는 것은 즉시 수정.
