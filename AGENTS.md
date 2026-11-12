# AGENTS.md — AI 코딩 에이전트 안내

이 저장소는 실시간 방송 플랫폼(라이브 송출 · 시청 웹 서비스) 공모전 결과물이다. 백엔드 · 프론트엔드 · 인프라를 AI 코딩 에이전트와 함께 만들었다. 이 파일은 에이전트가 이 저장소에서 작업을 시작할 때 필요한 것을 모은 안내다.

## 1. 먼저 읽을 것
- 설계서 `docs/DESIGN.md`. 이름(테이블 · 컬럼 · 엔드포인트 · 파일 · 설정 키)은 설계서의 것을 그대로 쓴다.
  - §0 구현 에이전트 사용법, §9 항목별 구현 지침(읽을 절 · 완료 기준)
  - 백엔드 API 계약: §4.4 응답 · 에러 형식, §4.5 시각(UTC ISO-8601), §4.7 인증, §4.14 STOMP, §4.15 SSE, §4.16 REST 엔드포인트 요약
  - 프론트엔드 구조: §6 (스택, 디렉터리, 라우트, 인증 처리, 미디어 모듈, STOMP, 화면별 요소)
- 설계에 빈틈이 있거나 API가 설계서와 다르면 임의로 정하지 말고 질문으로 남긴다.

## 2. 구성
| 경로 | 내용 |
|---|---|
| `backend/` | Spring Boot (Java). API · 인증 · 방송 상태 · 채팅(STOMP) · 후원 · 녹화 |
| `frontend/` | React + Vite + TypeScript SPA |
| `infra/` | docker compose(운영 `compose.yaml`, 로컬 `compose.dev.yaml`) · Caddy · MediaMTX · tracker |
| `docs/DESIGN.md` | 설계서 |

- 의존성은 설계서(§6.1 등)에 적힌 것만 쓴다. 새 패키지가 필요하면 먼저 묻는다.

## 3. 실행 · 확인
```bash
# 백엔드
cd backend && ./gradlew test

# 프론트엔드
cd frontend
npm install
npm run dev      # http://localhost:5173
npm run build    # tsc -b + vite build
npm run lint
npm test         # vitest run
```
- **테스트 범위:** 작업 중에는 바꾼 파일과 그것을 쓰는 곳의 테스트만 돌린다. 전체는 푸시 · PR 직전에 한 번.
  - 프론트: `npx vitest related --run <바꾼 파일…>`, 폴더 하나는 `npx vitest run src/<폴더>`. `api/types.ts` · 공용 훅 · 라우터를 바꿨으면 `npm run build`로 타입 영향까지 본다.
  - 백엔드: `./gradlew test --tests '*<패키지 또는 클래스>*'`
- 로컬에서 함께 띄우기: `docker compose -f infra/compose.dev.yaml up`으로 MySQL · MediaMTX · Caddy · tracker를 띄우고, backend(8080, `local` 프로파일)와 Vite(5173)를 실행한 뒤 **`https://localhost`**로 접속한다. Caddy가 `/api` · `/ws` · `/live/*`를 각 서버로, 나머지를 5173으로 프록시한다(§2.1 · §2.2).
- 같은 출처(same-origin) 전제다. API는 상대 경로(`/api/...`), STOMP는 `wss://<host>/ws`, WHIP/WHEP는 `/live/{channelId}/whip|whep`를 쓴다. CORS 설정은 없다.
- 브라우저 제약: `getDisplayMedia` · 카메라 · 마이크는 HTTPS 또는 localhost에서만 동작한다. PC 송출은 Chrome이 기준이다(탭 소리 공유).

## 4. 핵심 규칙 요약 (상세는 설계서)
- **인증**(§6.4):
  - Access JWT는 메모리(Zustand)에만 두고, Refresh는 HttpOnly 쿠키다(`Path=/api/auth`).
  - 앱이 시작되면 `POST /api/auth/refresh`로 로그인 상태를 복구한다.
  - 401이 오면 refresh를 한 번 하고 재시도한다. 또 401이면 로그아웃 상태로 바꾼다. 서버는 헤더가 붙은 만료 토큰에는 공개 경로라도 401을 준다(§4.7).
  - STOMP 연결 직전과 송출 시작 직전에는 `ensureFreshToken()`을 부른다. STOMP CONNECT가 `TOKEN_EXPIRED`로 거절되면 refresh 후 재연결한다(§6.4 · §6.6).
  - 보호 경로에서 비로그인이면 `/login?next=`로 보낸다.
- **권한:** 목록 · 상세 · 시청은 비로그인도 허용한다. 수정 · 삭제 · 종료 버튼은 응답의 `isOwner`가 참일 때만 보인다. 최종 판단은 서버가 한다.
- **방송 상태:** `LIVE → DISCONNECTED →(60초) ENDED`. DISCONNECTED에서는 "재연결 대기" 오버레이를, ENDED에서는 종료 화면(다시보기 버튼 + 추천)을 보여 준다.
- **송출:**
  - 송출 URL은 `channels/me.publish.whipUrl + '?src=pc'`(모바일은 `?src=mobile`), `Authorization: Bearer <streamKey>`.
  - 종료 순서는 **`whip.close()` → `stream.stop()` → `POST /api/broadcasts/{id}/end`**. 송출을 먼저 끊어 자동 재시도가 돌지 않게 한 뒤 서버에 알린다. 서버는 종료 뒤 30초 동안 같은 채널의 송출을 거절한다(§4.6 · §6.5).
- **에러 응답:** `{ code, message }`. 화면 문구는 `message`를 쓰고, 분기는 `code`로 한다.

## 5. 커밋
- 메시지 형식은 `type: 한글 설명`이다(예: `feat: 로그인 · 가입 화면 추가`). `git log`로 기존 형식을 확인한다.
- 여러 에이전트가 같은 체크아웃의 `main`을 함께 쓴다. 커밋할 때는 자기 파일만 지정해서 스테이징한다(`git add -A` · `git add .` 금지). 브랜치를 바꾸거나 rebase · reset · stash를 하지 않는다.
- 커밋 하나에는 논리적 변경 하나만 담고, 구현과 그 테스트는 같은 커밋에 둔다.
- 비밀 값(토스 시크릿 키, 운영 비밀번호, 토큰)은 커밋하지 않는다. 값은 루트 `.env`(gitignored)에만 두고, `.env.example`에는 키 이름과 예시만 둔다.
