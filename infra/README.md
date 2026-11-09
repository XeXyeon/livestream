# infra

| 파일 | 용도 |
|---|---|
| `compose.dev.yaml` | 로컬 개발: mysql · mediamtx · caddy · tracker (backend · frontend 는 호스트에서 실행) |
| `compose.yaml` | 운영: 위 + backend 컨테이너, 프론트 정적 서빙 |
| `Caddyfile` · `frontend-*.caddy` | 라우팅 (DESIGN §2.2). `FRONTEND_MODE=dev`면 Vite 로 프록시, `static`이면 `frontend/dist` |
| `mediamtx.yml` · `mediamtx/` | MediaMTX 설정 · curl 이 들어간 이미지 |
| `tracker/` | wt-tracker 이미지 (그리드 P2P 피어 소개) |

## 로컬 실행
```bash
docker compose -f infra/compose.dev.yaml up -d --build   # 저장소 루트에서
cd backend && ./gradlew bootRun                          # 또는 IDE 에서 LivestreamApplication (기본 local 프로파일)
cd frontend && npm run dev -- --host                     # --host 필수: Caddy 컨테이너가 접속해야 한다
```
- 접속은 **https://localhost** (Caddy 내부 CA 인증서라 브라우저 경고가 한 번 뜬다).
- OBS: 서버 `rtmp://localhost/live`, 스트림 키 `1?user=obs&pass=x` (채널 id 1). 인증 콜백(BC-1)이 붙기 전에는 아무 값이나 통과한다.
- 호스트에서 MySQL 이 이미 3306 을 쓰고 있으면(예: Homebrew) `MYSQL_PORT=3307 docker compose -f infra/compose.dev.yaml up -d` 로 포트를 바꾸고 backend 에 `SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/livestream?serverTimezone=Asia/Seoul&characterEncoding=UTF-8` 를 준다.
- 녹화 · 업로드 파일은 저장소 루트 `.local/`(git 무시)에 쌓인다. 지우려면 `rm -rf .local` 과 `docker compose -f infra/compose.dev.yaml down -v`.
- 값을 바꾸려면 루트 `.env`(`.env.example` 복사)를 만들고 `--env-file .env` 를 붙인다.
