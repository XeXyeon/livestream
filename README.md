# 그리드라이브 (GridLive)

**설치 없이 브라우저 하나로 방송하고, 시청자가 늘면 시청자끼리 영상을 나눠 받아 서버 부담을 줄이는 실시간 라이브 방송 웹 서비스입니다.**

| 바로가기 | |
|---|---|
| **서비스 주소** | **https://gridlive.duckdns.org** |
| **시연 영상** | **[`docs/demo/gridlive-demo.mp4`](docs/demo/gridlive-demo.mp4)** (4분 57초, 운영 서버에서 촬영) · 장면 목록은 아래 [시연 영상](#시연-영상) 절 |
| **테스트 계정** | 아래 [테스트 계정](#테스트-계정) 절 (방송인 · 시청자, 비밀번호 공용) |
| **기능 확인 방법** | 아래 [기능 확인](#기능-확인) 절 (PC · 모바일 송출, 시청, 종료, 지원 환경) |
| **실행 방법** | 아래 [실행 방법](#실행-방법) 절 (실행 환경 · 환경변수 예시) |

## 한눈에 보기

| | |
|---|---|
| 송출 | PC 화면 + 탭 · 시스템 소리 + 마이크 믹싱, 휴대폰 카메라 + 마이크, OBS · Larix(스트림 키, WHIP · RTMP) |
| 시청 | WebRTC(WHEP) 저지연 재생, 시청자가 많으면 **그리드 모드**(HLS + 시청자 간 P2P)로 자동 전환 |
| 소통 | 실시간 채팅(STOMP), 토스페이먼츠 후원(채팅 강조 · 화면 알림 · OBS 알림 위젯 + 음성 읽기), 다시보기 댓글, 채널 커뮤니티, 팔로우 |
| 남기기 | 자동 녹화 → 다시보기, **라이브 중 최근 2분에서 구간을 골라 만드는 클립**, 다시보기 구간 클립 |
| 찾기 | 헤더 통합 검색(채널 · 라이브 · 다시보기), 카테고리 필터, 종료 화면 추천, 페이지별 탭 제목 · 공유 태그 |
| 품질 | 백엔드 테스트 283개 · 프론트 테스트 202개(31개 파일), 운영 서버에서 실제 브라우저로 송출 · 시청 · 클립 · 다시보기 재생 확인 (2026-11-16 기준) |

## 시연 영상

**[▶ 시연 영상 보기 (`docs/demo/gridlive-demo.mp4`)](docs/demo/gridlive-demo.mp4)** — 4분 57초, 소리 없음, 장면마다 자막

운영 서버(https://gridlive.duckdns.org)에서 아래 시연 계정으로 찍었습니다. 방송 화면은 데스크톱 Chrome의 탭 공유로 보낸 SQL 강의 영상이고, 두 창을 쓰는 장면은 왼쪽이 방송인, 오른쪽이 시청자입니다.

| 시각 | 장면 | 보여 주는 것 |
|---|---|---|
| 0:04 | 홈 · 검색 | 비로그인 둘러보기, 카테고리 필터, 헤더 통합 검색 |
| 0:22 | 로그인 | 로그인 모달 |
| 0:33 | PC 스튜디오 | 제목 · 카테고리, Chrome 탭 공유(화면 + 탭 소리), 마이크 · 음량 믹서 |
| 0:47 | 송출 시작 → 시청 | WHIP 송출 → 홈 LIVE 카드 → WebRTC(WHEP) 저지연 시청 |
| 1:13 | 실시간 채팅 | 시청자 ↔ 방송인 STOMP 채팅, 방송인 배지 |
| 1:35 | 방송 중 정보 수정 | 제목을 바꾸면 시청 화면에 바로 반영 |
| 1:47 | 그리드 모드 | 시청자가 늘어난 방송에 들어오면 HLS + 시청자 간 P2P 재생, 서버 · 피어 전송량과 절감률 표시 |
| 2:14 | 휴대폰 시청 | 휴대폰 화면 배치(채팅 · 재생 모드) |
| 2:29 | 라이브 클립 | 최근 2분 초안 → 손잡이로 구간 선택 → 구간 재생 → 클립 페이지 |
| 3:06 | 방송 종료 | 종료 안내 · 다시보기 버튼 · 추천 방송 |
| 3:23 | 다시보기 · 댓글 | 자동 녹화된 다시보기 재생, 댓글 |
| 3:42 | 다시보기 클립 | 시작 위치 입력 · 끌기, 길이 선택 → 클립 |
| 4:23 | 채널 · 팔로우 | 채널 탭(다시보기 · 클립 · 커뮤니티), 팔로우 → 사이드바 |

- 영상에 없는 기능: 휴대폰 송출, 후원(토스페이먼츠 테스트 결제 · 알림 위젯), 송출이 끊겼다 이어지는 재연결, OBS 송출. 아래 [기능 확인](#기능-확인) 절의 순서로 직접 확인할 수 있습니다.
- 클립 처리 대기(몇 초)는 영상에서 빨리 감았습니다.

## 테스트 계정

https://gridlive.duckdns.org 에는 아래 시연용 계정이 미리 들어 있습니다. 오른쪽 위 **로그인**에서 이메일과 비밀번호를 넣으면 됩니다. 이 비밀번호는 시연 전용이며 다른 곳에서 쓰지 않습니다.

| 역할 | 이메일 | 비밀번호 | 미리 들어 있는 것 |
|---|---|---|---|
| 방송인 | `demo-streamer@example.com` | `gridlive-demo-2026` | 채널 "데모 방송인", 게시글 "첫 공지" |
| 시청자 | `demo-viewer@example.com` | `gridlive-demo-2026` | 로그인 · 채팅 · 후원 확인용 시청자 |

- 다른 기기에서 시청을 확인할 때는 방송인 계정으로 송출하고, 시청자 계정 또는 비로그인 상태로 채널 페이지를 엽니다.
- 홈 · 검색에 보이는 다시보기는 실제 송출로 남긴 방송입니다(채널 "유저1" · "유저2").
- 직접 가입한 새 계정으로도 모든 기능을 쓸 수 있습니다.

## 기능 확인

### 지원 환경
| 용도 | 기준 환경 | 비고 |
|---|---|---|
| PC 송출 | 데스크톱 **Chrome** 최신 버전 | 탭 소리는 Chrome의 **탭 공유**에서만 함께 보낼 수 있습니다. 창 · 전체 화면 공유는 영상만 갑니다(마이크는 함께 섞임) |
| 모바일 송출 | Android **Chrome**, iPhone **Safari** | 카메라 · 마이크 권한을 허용해야 합니다 |
| 시청 | PC · 모바일의 최신 Chrome · Safari · Edge | 로그인 없이 볼 수 있습니다. 채팅 · 후원은 로그인 필요 |
| 공통 | **HTTPS 주소로 접속** | 브라우저가 HTTPS에서만 화면 공유 · 카메라 · 마이크를 허용합니다 |

### PC 방송 송출
1. 방송인 계정으로 로그인하고 오른쪽 위 **방송하기**를 누릅니다. PC에서는 PC 스튜디오(`/studio/pc`)가 열립니다.
2. **방송 제목**과 카테고리를 입력합니다.
3. **화면 공유 시작** → Chrome 공유 창에서 **Chrome 탭**을 고르고 **탭 오디오도 공유**를 켠 뒤 공유합니다. 마이크 권한을 허용하면 탭 소리와 마이크가 한 오디오로 섞이고, 슬라이더로 각각 음량을 조절합니다.
4. **송출 시작**을 누르면 상태가 `LIVE`로 바뀌고 홈의 라이브 목록에 나타납니다.

### 모바일 방송 송출
1. 휴대폰 브라우저로 서비스에 접속해 로그인하고 **방송하기**를 누릅니다. 모바일 기기는 모바일 스튜디오(`/studio/mobile`)로 자동 이동합니다(PC에서는 `/studio/mobile` 주소로 직접 열 수 있습니다).
2. 카메라 · 마이크 권한을 허용하면 미리보기가 나옵니다. 위쪽 **방송 제목을 입력하세요**를 눌러 제목을 넣고, 카메라 버튼으로 전 · 후면을 바꿉니다.
3. **시작**을 누르면 송출이 시작됩니다.

### 시청
1. 다른 기기나 다른 브라우저(시크릿 창)에서 서비스에 접속합니다. 로그인하지 않아도 됩니다.
2. 홈의 **LIVE** 카드를 누르면 시청 화면(`/live/{채널 번호}`)에서 바로 재생됩니다. 화면 아래 컨트롤로 소리 · 영화관 모드 · 전체 화면 · PiP를 씁니다.
3. 시청자 계정으로 로그인하면 채팅과 토스페이먼츠 **테스트** 후원을 할 수 있습니다. 후원은 실제로 결제되지 않습니다.
4. 시청자가 2명 이상인 방송에 새로 들어오면 그리드 모드(HLS + 시청자 간 P2P)로 재생을 시작합니다. 이미 보고 있던 시청자는 플레이어 아래 **기본 · 그리드** 버튼으로 바꿀 수 있습니다. 플레이어 통계에서 서버 · 피어 전송량과 절감률을 봅니다.

### 종료
1. PC는 **방송 종료**, 모바일은 빨간 **종료** 버튼을 누릅니다. 송출 연결을 먼저 끊고 카메라 · 화면 공유를 멈춘 뒤 서버에 종료를 알립니다.
2. 시청 화면에는 몇 초 안에 종료 안내와 추천 방송이 나오고 재생이 멈춥니다.
3. 녹화가 자동으로 합쳐져 잠시 뒤 다시보기 목록과 마이페이지에 나타납니다.
- 송출이 네트워크 문제로 끊기면 60초 동안 `재연결 대기`로 기다렸다가 돌아오지 않으면 자동으로 종료됩니다.

## 어필 포인트

### 1. 시청자가 늘수록 서버가 덜 일하는 그리드 모드
- 기본 시청은 WebRTC(WHEP)라 지연이 짧지만, 시청자마다 서버가 영상을 한 벌씩 보내야 합니다.
- 방송의 시청자 수가 기준값(`APP_GRID_THRESHOLD`) 이상이면, 새로 들어오는 시청자는 서버가 알려 준 추천 모드(`recommendedMode`)에 따라 **그리드 모드**로 시작합니다. 보고 있던 시청자의 화면을 갑자기 바꾸지는 않습니다. HLS 조각을 [p2p-media-loader](https://github.com/Novage/p2p-media-loader)로 시청자끼리 주고받고, 서버는 피어에게서 못 받은 조각만 보냅니다. 피어 소개는 자체 호스팅한 WebTorrent 트래커(`infra/tracker/`)가 맡습니다.
- 플레이어 통계(`PlayerStats`)가 서버 · 피어에서 받은 바이트와 **절감률**을 실시간으로 보여 줍니다. 로컬 측정(시청 창 3개, 60초)에서 서버 전송량을 **56~67% 줄였습니다**.
- 시청자는 언제든 기본 · 그리드 모드를 직접 고를 수 있고(`ModeToggle`), 방송인 본인의 접속은 시청자 수에서 빼서 전환 기준이 흔들리지 않게 했습니다.
- LL-HLS의 부분 조각은 P2P로 교환되지 않는다는 것을 PoC로 확인하고, 그리드 모드만 조각 단위 재생으로 맞췄습니다(`docs/DESIGN.md` §8).

### 2. 브라우저만으로 하는 방송 스튜디오
- PC는 `getDisplayMedia`로 화면과 탭 소리를, `getUserMedia`로 마이크를 받아 **Web Audio로 음량을 따로 조절해 한 트랙으로 섞어** WHIP로 보냅니다.
- 휴대폰으로 `/studio`에 들어가면 기기를 알아보고 모바일 스튜디오(전 · 후면 카메라 전환)로 보냅니다.
- 송출 중 바꾼 제목 · 카테고리는 STOMP `info` 이벤트로 시청 화면에 바로 반영됩니다.
- 송출이 끊기면 `재연결 대기`(60초) 상태를 거쳐 종료되고, 다시 이어지면 시청자는 새로고침 없이 이어 봅니다.
- 송출 중 브라우저가 해상도를 바꾸거나(창 크기 · 회전 · 대역폭 적응) 녹화 첫 조각에 MediaMTX 기본값 SPS가 들어가면, 그대로 이어 붙인 다시보기가 Chrome에서 몇 초 만에 디코딩 오류로 멈추는 것을 운영에서 찾았습니다. 병합 · 클립 처리를 libx264 재인코딩(긴 변 1920 이하)으로 바꾸고, 영상이 없는 결과는 실패로 처리하며, 이미 있던 파일은 백필 스크립트(`infra/scripts/reencode-media.sh`)로 다시 인코딩했습니다.
- 하드웨어 H.264 인코더의 첫 프레임이 늦어 영상 트랙이 빠지던 문제를 찾아 MediaMTX 트랙 수집 대기를 늘리고, 브라우저 송출 코덱을 H.264 우선으로 협상해 녹화 · 클립까지 같은 코덱으로 이어지게 했습니다.

### 3. 라이브 클립: 방금 지나간 장면을 바로 잘라 내기
- 시청 중 클립 버튼을 누르면 서버가 MediaMTX 재생 서버에서 **최근 2분을 임시 초안으로 받아 오고**, 사용자는 미리보기 영상 위의 **두 손잡이 드래그 바**(`ClipRangeBar`)로 구간을 고릅니다. 손잡이를 움직이면 영상이 그 지점으로 따라가고, 키보드 · 터치도 됩니다.
- 재생 서버(내부 전용 포트)는 바깥에 열지 않고 백엔드가 대신 받아 만든 사람에게만 토큰으로 보여 줍니다.
- 브라우저 송출 직후 녹화 조각이 갈라지는 MediaMTX 동작을 실측으로 찾아내, 조각 목록(`/list`)을 보고 이어지는 조각에 창을 맞추도록 고쳤습니다.
- 처리 중 서버가 재시작돼도 이어서 처리하고, 오래 갇힌 작업은 1분마다 정리합니다. 초안은 10분 뒤 자동 삭제됩니다.

### 4. 틀리면 티가 안 나는 곳을 먼저 막았습니다
- **후원 중복 승인 방지:** 같은 결제를 동시에 여러 번 승인해도 한 번만 저장됩니다(`DonationConfirmIdempotencyTest`).
- **소유자 검증:** 방송 · 채널 · 다시보기 · 클립 · 댓글 · 게시글의 수정 · 삭제 · 송출 제어는 서버가 소유자를 확인하고, 각 `*ApiTest`가 제3자 요청이 403인지 검사합니다.
- **STOMP 목적지 제한:** 클라이언트가 방송 토픽에 직접 메시지를 보내 가짜 채팅 · 후원을 뿌리지 못하게 SEND · SUBSCRIBE 목적지를 제한했습니다.
- **인증:** Access 토큰은 메모리에만, Refresh 토큰은 HttpOnly 쿠키에 둡니다. 만료되면 한 번 갱신하고 재시도하며, STOMP 연결과 송출 시작 직전에도 토큰을 새로 고칩니다.
- **비공개 영상:** 비공개 다시보기 · 클립은 5분짜리 재생 토큰으로만 열립니다.
- **동시성:** 클립 초안 생성에서 실제로 재현한 데드락을 격리 수준과 잠금 순서로 해결했습니다(`docs/DESIGN.md` §4.18).

### 5. 설계서 하나로 AI 에이전트와 함께 만든 과정
- `docs/DESIGN.md`(설계서)를 단일 기준으로 두고, 항목별 구현 지침(§9)과 완료 기준을 먼저 정한 뒤 구현했습니다. 에이전트용 안내는 `AGENTS.md`에 있습니다.
- 구현 뒤에는 실제 브라우저로 송출 → 다른 창 시청 → 클립 · 다시보기 재생까지 직접 실행해 확인했고, 그 과정에서 찾은 문제(트랙 누락, 녹화 조각 갈라짐, 데드락 등)와 해결 근거를 설계서 §8에 남겼습니다.

## 기능 목록

| 영역 | 기능 |
|---|---|
| 송출 | PC 화면 · 탭 소리 · 마이크 믹싱, 모바일 카메라 · 마이크(전 · 후면), 기기 자동 판별, OBS · Larix 스트림 키(WHIP · RTMP), 스트림 키 재발급 |
| 방송 관리 | 시작 · 송출 중 정보 수정 · 종료 · 삭제, 상태 `LIVE → 재연결 대기 → 종료`, 라이브 썸네일 자동 캡처 |
| 시청 | WHEP 저지연 재생, 그리드 모드(HLS + P2P) 자동 전환, 재생 · 소리 · 영화관 모드 · 전체 화면 · PiP, 종료 화면 추천, 오프라인 채널 화면 |
| 채팅 · 후원 | STOMP 채팅(입장 시 최근 50개), 시청자 수, 채팅 팝업 창, 토스페이먼츠 테스트 후원 · 채팅 강조 · 화면 알림, 방송별 후원 내역 · 합계 |
| 알림 위젯 | OBS 브라우저 소스용 후원 알림 위젯(SSE, 끊겨도 놓친 후원 이어 받기) + 음성 읽기(`speechSynthesis`) |
| 다시보기 | 자동 녹화 · 병합(재인코딩) · 썸네일, 공개 · 비공개 설정, 보관 기간, 댓글 CRUD |
| 클립 | 라이브 최근 2분 초안 → 드래그 구간 선택, 다시보기 구간 클립, 클립 페이지 · 채널 클립 탭 · 내 클립 |
| 채널 | 채널 홈 · 다시보기 · 커뮤니티(게시글 · 댓글), 팔로우 · 팔로워 수, 사이드바 팔로우 채널 |
| 검색 · 노출 | 헤더 통합 검색(채널 · 라이브 · 다시보기), 카테고리 필터, 페이지별 탭 제목 · canonical · noindex, 공유 태그(OG · Twitter), `robots.txt` |
| 계정 | 가입 · 로그인(로그인 모달) · 로그아웃, 마이페이지 |

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

- 같은 출처 구성이라 CORS 설정이 없습니다. 자세한 설계는 `docs/DESIGN.md`를 보세요.

## 실행 방법

### 실행 환경
| 구분 | 버전 |
|---|---|
| 운영 서버 | Linux + Docker 29 · Docker Compose v2 이상 (배포 서버: Google Cloud e2-standard-2, 메모리 8 GB, Debian 13) |
| 로컬 개발 | Docker, JDK 25(Gradle 툴체인이 받음), Node.js 22 |
| 구성 요소 | Spring Boot 4.1, MySQL 8.4, MediaMTX 1.21.3, Caddy 2, React 19 · Vite 7 |

### 환경변수 예시
루트의 `.env.example`을 `.env`로 복사해 값을 바꿉니다. `.env`는 Git에 올라가지 않습니다. 로컬 개발은 `.env` 없이 기본값으로도 뜹니다.

```dotenv
PUBLIC_HOST=example.duckdns.org          # 서비스 도메인 (로컬은 localhost)
INTERNAL_TOKEN=<openssl rand -hex 32>     # MediaMTX 훅 → 백엔드 내부 호출 토큰
MYSQL_ROOT_PASSWORD=<임의 값>
SPRING_DATASOURCE_USERNAME=livestream
SPRING_DATASOURCE_PASSWORD=<임의 값>
JWT_SECRET=<32바이트 이상 임의 문자열>
APP_TOSS_CLIENT_KEY=test_gck_...          # 토스페이먼츠 결제위젯 테스트 키
APP_TOSS_SECRET_KEY=test_gsk_...
APP_GRID_THRESHOLD=2                      # 그리드 모드로 바꾸는 시청자 수 (시연 2, 기본 100)
APP_SEED_ENABLED=true                     # 첫 기동 때 시연 계정 · 다시보기 생성
APP_SEED_PASSWORD=gridlive-demo-2026      # 시연 계정 비밀번호
APP_RECORDING_RETENTION_DAYS=365          # 다시보기 보관 일수
```

### 운영 배포 (서버 한 대)
도메인이 서버 IP를 가리키고, 80 · 443 · 1935/TCP와 8189/UDP가 열려 있어야 합니다. Caddy가 인증서를 자동으로 받습니다.

```bash
git clone https://github.com/XeXyeon/livestream.git && cd livestream
cp .env.example .env    # 위 예시처럼 값 채우기
docker run --rm -v "$PWD/frontend":/app -w /app node:22 sh -c "npm ci && npm run build"
docker compose --env-file .env -f infra/compose.yaml up -d --build
```

### 로컬 개발

```bash
docker compose -f infra/compose.dev.yaml up -d --build   # MySQL · MediaMTX · Caddy · tracker
```
```bash
cd backend && ./gradlew bootRun                          # 8080, local 프로파일
```
```bash
cd frontend && npm install && npm run dev -- --host      # 5173
```

- **https://localhost** 로 접속합니다(로컬 인증서라 브라우저 경고가 한 번 뜹니다).
- 후원 결제를 확인하려면 루트 `.env`에 토스페이먼츠 결제위젯 **테스트 키**를 넣습니다. 더 자세한 내용은 `infra/README.md`에 있습니다.

### 테스트
```bash
cd backend && ./gradlew test
```
```bash
cd frontend && npm run lint && npm run build && npm test
```

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
| 클립 (라이브 초안 · 구간 선택 · 다시보기 구간) | `frontend/src/components/clip/` (`ClipRangeBar`) · `frontend/src/pages/Clip.tsx` | `clip/ClipController` · `clip/ClipDraftController` · `clip/ClipProcessingService` · `mediamtx/MediaMtxClient` | `infra/mediamtx.yml` (`playback`, 내부 전용) |
| 그리드 모드 (HLS + P2P) | `frontend/src/media/gridPlayer.ts` · `frontend/src/components/player/GridView.tsx` · `PlayerStats.tsx` · `ModeToggle.tsx` | `chat/ViewerCounter` (시청자 수) | `infra/tracker/` |
| 통합 검색 · 팔로우 | `frontend/src/pages/Search.tsx` · `frontend/src/components/sidebar/ChannelSidebar.tsx` | `search/SearchController` · `follow/FollowController` | - |
| 알림 위젯 (SSE) | `frontend/src/pages/Widget.tsx` | `integration/WidgetEventService` | - |
| 탭 제목 · 공유 태그 · robots | `frontend/src/components/layout/usePageMeta.ts` · `frontend/index.html` · `frontend/public/robots.txt` | - | - |

### 송출 구현 요약

- **PC 화면 캡처 송출:** `createPcStream`(`frontend/src/media/mixer.ts`)이 `navigator.mediaDevices.getDisplayMedia({ video, audio: true })`로 화면 영상을 캡처하고, `WhipClient.publish`(`frontend/src/media/whip.ts`)가 `RTCPeerConnection`으로 WebRTC offer를 만들어 WHIP(HTTP POST)로 MediaMTX에 실시간 송출합니다.
- **탭/시스템 소리 + 마이크 동시 송출:** 같은 `createPcStream`이 `getDisplayMedia`로 받은 탭 · 시스템 소리 트랙과 `getUserMedia({ audio })`로 연 마이크 트랙을 `AudioContext`의 `GainNode`로 각각 조절해 `MediaStreamAudioDestinationNode` 하나로 합치고, 이 오디오와 화면 영상을 한 `MediaStream`으로 묶어 보냅니다.
- **모바일 카메라 + 마이크 송출:** `createMobileStream`(같은 파일)이 `getUserMedia({ video: { facingMode }, audio: true })`로 휴대폰 카메라 영상과 마이크 음성을 함께 받아 같은 `WhipClient`로 송출합니다(`frontend/src/pages/StudioMobile.tsx`, 전 · 후면 전환 지원). 화면 공유를 못 하거나 모바일 UA면 `frontend/src/media/devices.ts`의 `studioTarget`이 모바일 스튜디오로 보냅니다.
- **종료 순서:** `useBroadcastSession`(`frontend/src/components/studio/useBroadcastSession.ts`)의 `end`가 `whip.close()` → 스트림 정지 → `POST /api/broadcasts/{id}/end` 순으로 처리합니다.
- **라우트:** `/studio/pc`, `/studio/mobile` (`frontend/src/App.tsx`).

## 오픈소스 · 외부 서비스 출처

이 프로젝트는 아래 오픈소스를 의존성 또는 컨테이너 이미지로 사용합니다. 소스 코드를 복사해 넣은 것은 없으며, 각 라이선스는 해당 저장소를 따릅니다.

### 스트리밍 · 인프라
| 이름 | 용도 | 라이선스 | 출처 |
|---|---|---|---|
| MediaMTX 1.21.3 (`bluenviron/mediamtx:1.21.3-ffmpeg`) | 미디어 서버: WHIP 송출 · WHEP 시청 · RTMP · LL-HLS · 녹화 | MIT | https://github.com/bluenviron/mediamtx |
| wt-tracker (커밋 `33127a5`) | 그리드 모드 피어 소개용 WebTorrent 트래커 | Apache-2.0 | https://github.com/Novage/wt-tracker |
| Caddy 2 (`caddy:2`) | HTTPS(자동 인증서) · 리버스 프록시 · 정적 파일 | Apache-2.0 | https://github.com/caddyserver/caddy |
| MySQL 8.4 (`mysql:8.4`) | 데이터베이스 | GPL-2.0 | https://github.com/mysql/mysql-server |
| FFmpeg | 녹화 조각 병합 · 재인코딩 · 클립 자르기 · 썸네일 · 길이 측정 | LGPL-2.1+ / GPL-2.0+ | https://ffmpeg.org |

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
