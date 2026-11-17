# 실행 방법

구성도는 [README](../README.md#구성)에, 인프라 상세(compose · Caddy · MediaMTX 설정)는 [`infra/README.md`](../infra/README.md)에 있습니다.

## 실행 환경
| 구분 | 버전 |
|---|---|
| 운영 서버 | Linux + Docker 29 · Docker Compose v2 이상 (배포 서버: Google Cloud e2-standard-2, 메모리 8 GB, Debian 13) |
| 로컬 개발 | Docker, JDK 25(Gradle 툴체인이 받음), Node.js 22 |
| 구성 요소 | Spring Boot 4.1, MySQL 8.4, MediaMTX 1.21.3, Caddy 2, React 19 · Vite 7 |

## 환경변수 예시
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

## 운영 배포 (서버 한 대)
도메인이 서버 IP를 가리키고, 80 · 443 · 1935/TCP와 8189/UDP가 열려 있어야 합니다. Caddy가 인증서를 자동으로 받습니다.

```bash
git clone https://github.com/XeXyeon/livestream.git && cd livestream
cp .env.example .env    # 위 예시처럼 값 채우기
docker run --rm -v "$PWD/frontend":/app -w /app node:22 sh -c "npm ci && npm run build"
docker compose --env-file .env -f infra/compose.yaml up -d --build
```

## 로컬 개발

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
- 후원 결제를 확인하려면 루트 `.env`에 토스페이먼츠 결제위젯 **테스트 키**를 넣습니다.

## 테스트
```bash
cd backend && ./gradlew test
```
```bash
cd frontend && npm run lint && npm run build && npm test
```
