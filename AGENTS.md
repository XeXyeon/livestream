# AGENTS.md — 프론트엔드 담당(Codex) 인수인계

이 저장소는 실시간 방송 플랫폼(라이브 송출 · 시청 웹 서비스) 공모전 결과물이다. 마감은 **2026-11-19(목) 23:59**이다.
백엔드 · 인프라 · 설계는 Claude 세션들이 맡고, **프론트엔드(`frontend/`)는 Codex가 맡는다.** 이 파일은 Codex가 혼자서 프론트 작업을 시작할 수 있도록 필요한 것을 모아 둔 안내다.

## 1. 먼저 읽을 것 (순서대로)
1. 공통 규칙: `~/.codex/AGENTS.md`(= `~/.work-dash/RULES.md`). 로드맵 · 작업 카드 · 이슈 · 커밋 규칙이 여기 있다.
2. 설계서: `docs/DESIGN.md`.
   - §0 구현 에이전트 사용법
   - §6 프론트엔드 구조: 스택, 디렉터리, 라우트, 인증 처리, 미디어 모듈, STOMP, 화면별 요소
   - §9에서 **자기 항목 ID 절**: 읽을 절과 완료 기준
3. API 계약은 백엔드 절에 있다. §4.4 응답 · 에러 형식, §4.5 시각(UTC ISO-8601), §4.7 인증, §4.14 STOMP, §4.15 SSE, §4.16 REST 엔드포인트 요약을 본다.
4. 동작 흐름 문서 `~/.work-dash/flows/livestream.md`가 있으면 코드를 훑기 전에 읽는다.

설계서와 작업 카드가 다르면 **설계서가 우선**이다.

## 2. 프로젝트 이름은 `livestream` (중요)
- 로드맵 · 작업 카드 · 이슈 · 흐름 파일의 프로젝트 이름은 항상 **`livestream`**이다.
  - `~/.work-dash/roadmaps/livestream.md`
  - `~/.work-dash/worklogs/livestream/<ID>.md`
  - `~/.work-dash/issues/livestream.md`
  - `~/.work-dash/flows/livestream.md`
- Codex는 **저장소 루트(`~/WebstormProjects/livestream`)에서 실행한다.** `frontend/`에서 실행하면 공통 규칙의 "현재 작업 폴더 이름"이 `frontend`가 되어 엉뚱한 로드맵을 찾는다.

## 3. 맡는 항목
| ID | 내용 | 선행(백엔드 등) |
|---|---|---|
| FE-1 | 프론트 기반 (라우터 · 인증 상태 · fetch 래퍼 · 레이아웃 · 로그인/가입 S7) | OPS-2 ✓ |
| FE-2 | PC 송출 스튜디오 S3 (믹서 · WHIP · 미리보기 · 상태) | FE-1, BC-1 |
| FE-3 | 모바일 송출 S4 · 기기 감지 진입 · 채널 설정 S5 | FE-2 |
| FE-4 | 홈 S1 · 시청 화면 S2 기본 모드 (WHEP · 유예/종료 화면 · 추천) | FE-1, BC-2 |
| FE-5 | 채팅 UI (S2 · S3) | CHAT-1, FE-4 |
| FE-6 | 후원 UI (결제위젯 · 채팅 강조 · 화면 알림 · 마이페이지 내역) | DON-1, FE-5 |
| FE-7 | 다시보기 S6 · 마이페이지 S8 · 채널 페이지 S9 | REC-2, FE-4 |
| FE-8 | 댓글 · 커뮤니티 UI (S9 탭 · S10) | CMT-1, POST-1, FE-7 |
| GRID-1 | 그리드 PoC (`frontend/poc/`, LL-HLS + p2p-media-loader · iOS Safari Opus 확인) | OPS-3 |
| GRID-2 | 그리드 모드 플레이어 · 자동 전환 | GRID-1, FE-4 |

- 선행 항목이 끝났는지는 로드맵의 `[x]`로 확인한다.
- 지금 바로 시작할 수 있는 항목은 **FE-1**이다.
- 백엔드가 아직 없는 화면도 §4.16의 응답 모양대로 `api/types.ts`를 만들어 먼저 구현해도 된다. 실제 연결 확인은 해당 백엔드 항목이 `[x]`가 된 뒤에 하고, 카드의 "검증"에 확인한 것과 확인하지 못한 것을 나눠 적는다.
- 담당 태그는 `@gpt`이다.

## 4. 경계 (건드리지 않는 것)
- 수정은 **`frontend/` 안에서만** 한다. `backend/`, `infra/`, `docs/`, 루트 설정 파일은 Claude 세션들이 동시에 고치고 있다.
- API가 없거나, 설계서와 다르거나, 설계에 빈틈이 있을 때:
  - 백엔드를 고치지 않는다.
  - 작업 카드의 `## 열린 질문`에 적고, 로드맵 항목 아래에 `협의: …` 메모를 단다.
  - 사용자에게 "설계자 세션에 넘길 질문"으로 알린다. Codex는 Claude 세션에 직접 메시지를 보낼 수 없으므로 사용자가 전달한다.
  - 버그나 불일치는 `~/.work-dash/issues/livestream.md`에 적는다.
- 의존성은 §6.1에 적힌 것만 쓴다. 이미 `package.json`에 설치돼 있다. 새 패키지가 필요하면 먼저 묻는다.
- 이름(파일 · 컴포넌트 · 경로 · API 필드)은 설계서 §6.2 · §6.3 · §4.16의 것을 그대로 쓴다.

## 5. 실행 · 확인
```bash
cd frontend
npm install
npm run dev      # http://localhost:5173
npm run build    # tsc -b + vite build
npm run lint
npx vitest run   # 아직 test 스크립트가 없다. FE-1에서 "test": "vitest run"을 추가해도 된다
```
- 로컬에서 백엔드와 붙이는 방법:
  - **OPS-3 이후:** `docker compose -f infra/compose.dev.yaml up`으로 띄우고, IDE에서 backend(8080)를 실행한 뒤 **`https://localhost`**로 접속한다. Caddy가 `/api` · `/ws` · `/live/*`를 각 서버로, 나머지를 5173으로 프록시한다(§2.1 · §2.2).
  - **OPS-3 전:** `vite.config.ts`에 `server.proxy`(`/api`, `/ws` → `http://localhost:8080`)를 두고 개발해도 된다. 설정만 추가하고 새 의존성은 넣지 않는다.
- 같은 출처(same-origin) 전제다. API는 상대 경로(`/api/...`), STOMP는 `wss://<host>/ws`, WHIP/WHEP는 `/live/{channelId}/whip|whep`를 쓴다. CORS 설정은 없다.
- 브라우저 제약:
  - `getDisplayMedia`, 카메라, 마이크는 HTTPS 또는 localhost에서만 동작한다.
  - 휴대폰으로 확인할 때는 Caddy 내부 CA를 폰에 신뢰시키거나, OPS-5 이후 운영 도메인을 쓴다.
  - PC 송출은 Chrome이 기준이다(탭 소리 공유).

## 6. 핵심 규칙 요약 (상세는 설계서)
- **인증**(§6.4):
  - Access JWT는 메모리(Zustand)에만 두고, Refresh는 HttpOnly 쿠키다(`Path=/api/auth`).
  - 앱이 시작되면 `POST /api/auth/refresh`로 로그인 상태를 복구한다.
  - 401이 오면 refresh를 한 번 하고 재시도한다. 또 401이면 로그아웃 상태로 바꾼다.
  - 보호 경로에서 비로그인이면 `/login?next=`로 보낸다.
- **권한:** 목록 · 상세 · 시청은 비로그인도 허용한다. 수정 · 삭제 · 종료 버튼은 응답의 `isOwner`가 참일 때만 보인다. 최종 판단은 서버가 한다.
- **방송 상태:** `LIVE → DISCONNECTED →(60초) ENDED`. DISCONNECTED에서는 "재연결 대기" 오버레이를, ENDED에서는 종료 화면(다시보기 버튼 + 추천)을 보여 준다.
- **송출 URL:**
  - 송출: `channels/me.publish.whipUrl + '?src=pc'`(모바일은 `?src=mobile`), `Authorization: Bearer <streamKey>`
  - 종료: `POST /api/broadcasts/{id}/end` → `whip.close()` → `stream.stop()`
- **에러 응답:** `{ code, message }`. 화면 문구는 `message`를 쓰고, 분기는 `code`로 한다.

## 7. 커밋
- 커밋 메시지 형식은 `type: 한글 설명`이다(예: `feat: 로그인 · 가입 화면 추가`). `git log`로 기존 형식을 확인한다.
- Claude 세션들과 **같은 체크아웃의 `main` 브랜치**를 함께 쓴다.
  - 커밋할 때는 `git add frontend/…`처럼 **자기 파일만 지정해서** 스테이징한다. `git add -A`나 `git add .`은 쓰지 않는다.
  - 브랜치를 바꾸거나, rebase · reset · stash를 하지 않는다.
- 커밋 하나에는 논리적 변경 하나만 담는다. 구현과 그 테스트는 같은 커밋에 둔다(공통 규칙).
- 비밀 값(토스 시크릿 키 등)은 커밋하지 않는다. 프론트에서 쓰는 값은 토스 **클라이언트 키(테스트)**뿐이며, 이 값은 `POST /broadcasts/{id}/donations` 응답의 `clientKey`로 받는다.
