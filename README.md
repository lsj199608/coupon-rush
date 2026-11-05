# Coupon Rush

대량 동시 요청에서도 정확히 선착순으로 발급되는 쿠폰/티켓팅 서비스입니다.
Redis(원자적 재고 차감), Kafka(비동기 발급), 소셜 로그인(카카오·네이버·구글) + JWT, React 화면을 단계적으로 적용한 학습·포트폴리오 프로젝트입니다.

| 영역 | 기술 |
|---|---|
| Backend | Spring Boot 3.5, Java 21, Gradle, JPA, Flyway, Spring Security OAuth2 Client + JWT |
| Data | PostgreSQL 17 (pgvector), Redis 7.4, Kafka 3.9 (KRaft 단일 노드) |
| Frontend | React 19 + Vite 6 + TypeScript, React Router 7, TanStack Query 5, axios |
| Infra | Docker Compose (로컬) |

설계와 측정 결과는 [`docs/concurrency-notes.md`](docs/concurrency-notes.md)에, 단계별 결정 사항은 [`CLAUDE.md`](CLAUDE.md)에 있습니다.

```
브라우저 ─ localhost:5173 (Vite) ─ 프록시 /api, /oauth2, /login/oauth2 ─▶ localhost:8080 (Spring Boot)
                                                                          ├─ PostgreSQL :5432
                                                                          ├─ Redis      :6379
                                                                          └─ Kafka      :9092
```

## 준비물

| 도구 | 버전 | 용도 |
|---|---|---|
| Docker Desktop | 최신 | Postgres / Redis / Kafka 컨테이너, 백엔드 테스트(Testcontainers) |
| Java (JDK) | 21 | 백엔드. Gradle은 `gradlew`가 내려받으므로 따로 설치하지 않음 |
| Node.js | 20 이상 | 프론트엔드 |

## 로컬 실행

아래 명령은 Windows PowerShell 기준입니다. macOS/Linux는 `.\gradlew`를 `./gradlew`로, `Copy-Item`을 `cp`로 바꾸면 됩니다.
**세 개의 터미널**에서 1 → 2 → 3 순서로 실행합니다.

### 0. 환경변수 파일 만들기 (처음 한 번)

```powershell
Copy-Item infra\.env.example infra\.env
```

`infra/.env`를 열어 **필수** 항목을 채웁니다 (아래 [환경변수](#환경변수) 참고). docker compose와 Spring Boot가 이 한 파일을 함께 읽습니다.
`.env`는 `.gitignore` 대상이라 커밋되지 않습니다.

### 1. 인프라: docker compose

```powershell
cd infra
docker compose up -d
docker compose ps        # postgres, redis, kafka 가 running(healthy) 인지 확인
```

| 컨테이너 | 포트 |
|---|---|
| coupon-rush-postgres | 5432 |
| coupon-rush-redis | 6379 |
| coupon-rush-kafka | 9092 |

데이터는 Docker 볼륨에 남습니다. 끄기는 `docker compose down`, 데이터까지 지우려면 `docker compose down -v`입니다.

### 2. 백엔드: Spring Boot (8080)

```powershell
cd backend
.\gradlew bootRun
```

- `infra/.env`를 `../infra/.env`로 읽기 때문에 **반드시 `backend/` 폴더에서** 실행합니다.
- 처음 기동할 때 Flyway가 스키마를 만들고, Kafka 토픽(`coupon-issue-requests`, `.DLT`)이 자동으로 생깁니다.
- 확인: `curl.exe http://localhost:8080/actuator/health` → `{"status":"UP"}`

### 3. 프론트엔드: Vite (5173)

```powershell
cd frontend
npm install              # 처음 한 번, package.json 이 바뀌었을 때
npm run dev
```

http://localhost:5173 에 접속합니다. 프론트엔드에는 환경변수가 없습니다. API 요청은 Vite 프록시가 8080으로 넘기므로 백엔드가 먼저 떠 있어야 합니다.

### 4. (선택) 관리자 계정으로 이벤트 만들기

1. 화면에서 소셜 로그인을 한 번 합니다.
2. 내 계정을 관리자로 바꿉니다.
   ```powershell
   docker exec -it coupon-rush-postgres psql -U <POSTGRES_USER> -d <POSTGRES_DB> -c "select id, provider, nickname, role from users;"
   docker exec -it coupon-rush-postgres psql -U <POSTGRES_USER> -d <POSTGRES_DB> -c "UPDATE users SET role='ADMIN' WHERE id=<내 id>;"
   ```
3. 브라우저를 새로고침합니다. 새 토큰을 받을 때 권한을 DB에서 다시 읽습니다. 헤더에 **이벤트 만들기** 메뉴가 생기면 성공입니다.
4. 폼으로 이벤트를 만들면 생성 직후 화면에 **Redis 재고 적재 여부**가 표시됩니다. 직접 확인하려면:
   ```powershell
   docker exec coupon-rush-redis redis-cli GET "event:{1}:stock"   # {1} = 이벤트 ID, 총 수량과 같아야 함
   ```

## 환경변수

모두 `infra/.env`에 둡니다. **선택** 항목은 줄을 아예 쓰지 않으면 기본값이 적용됩니다.

> ⚠ `KEY=`처럼 **빈 값으로 남기면 기본값이 아니라 빈 문자열**이 들어갑니다. 쓰지 않을 항목은 주석(`#`)으로 둡니다.
> 또 Spring이 이 파일을 `.properties` 형식으로 읽기 때문에, `KEY=value # 설명`처럼 **값 뒤에 주석을 붙이면 주석까지 값이 됩니다.**

### 필수

| 변수 | 사용처 | 설명 |
|---|---|---|
| `POSTGRES_DB` | compose, 백엔드 | DB 이름 (예: `coupon_rush`) |
| `POSTGRES_USER` | compose, 백엔드 | DB 계정 (예: `coupon`) |
| `POSTGRES_PASSWORD` | compose, 백엔드 | DB 비밀번호. 컨테이너를 **처음 만들 때** 적용되며, 나중에 바꾸려면 볼륨을 지우고 다시 만들어야 합니다 |
| `JWT_SECRET` | 백엔드 | JWT 서명 키, 32바이트 이상 랜덤 문자열.<br>생성: `[Convert]::ToBase64String((1..48 \| % { Get-Random -Max 256 }))` |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | 백엔드 | 카카오 디벨로퍼스 > 앱 키 "REST API 키" / 보안 > Client Secret |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 백엔드 | 네이버 개발자센터 > 내 애플리케이션 |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | 백엔드 | Google Cloud Console > 사용자 인증 정보 > OAuth 2.0 클라이언트 ID |

소셜 로그인 콘솔에는 Redirect URI로 `http://localhost:8080/login/oauth2/code/{kakao|naver|google}`을 등록합니다.
(쓰지 않는 제공자도 값은 있어야 기동됩니다. 임의 문자열을 넣어 두면 그 제공자 로그인만 실패합니다.)

### 선택

| 변수 | 기본값 | 설명 |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | DB 호스트 |
| `POSTGRES_PORT` | `5432` | DB 포트. compose가 여는 호스트 포트도 이 값 |
| `REDIS_HOST` | `localhost` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트. compose가 여는 호스트 포트도 이 값 |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka 브로커 주소 |
| `JWT_ACCESS_TOKEN_VALIDITY` | `30m` | access token 유효기간 |
| `JWT_REFRESH_TOKEN_VALIDITY` | `14d` | refresh token(HttpOnly 쿠키) 유효기간 |
| `AUTH_COOKIE_SECURE` | `false` | refresh 쿠키 Secure 속성. HTTPS 배포에서는 `true` |
| `FRONTEND_OAUTH2_REDIRECT_URI` | `http://localhost:5173/oauth/callback` | 로그인 성공/실패 후 백엔드가 돌려보낼 프론트 주소 |
| `COUPON_ISSUE_MODE` | `async` | `async`: Redis 판정 → Kafka → 202 + 결과 폴링 / `sync`: 바로 DB 저장 → 201 |
| `COUPON_ISSUE_STRATEGY` | `redis` | sync 모드의 재고 제어: `redis` \| `conditional-update` \| `pessimistic-lock` \| `optimistic-lock` |
| `COUPON_RECONCILE_ENABLED` | `true` | Redis ↔ DB 대조 점검 스케줄러 (10분, 불일치만 WARN 로그) |
| `KAFKA_TOPIC_PARTITIONS` | `3` | 발급 토픽 파티션 수 (늘리기만 가능) |
| `KAFKA_TOPIC_REPLICAS` | `1` | 토픽 복제 수 (로컬 단일 브로커는 1) |
| `KAFKA_CONSUMER_CONCURRENCY` | `3` | 서버 1대의 컨슈머 스레드 수 (파티션 수 이하) |

`.env`는 기동할 때만 읽습니다. 값을 바꾸면 `bootRun`(포트·DB 값이면 `docker compose`도)을 다시 시작하세요.

## 테스트

```powershell
cd backend;  .\gradlew test        # Testcontainers 로 Postgres/Redis/Kafka 를 따로 띄움 (Docker 실행 필요, .env 불필요)
cd frontend; npm test              # Vitest + React Testing Library
cd frontend; npm run build         # 타입 검사 + 프로덕션 빌드
cd backend;  .\gradlew benchmark   # (선택) 방식별 성능 측정, 약 12분 → build/benchmark/*.md
```

## 문제 해결

| 증상 | 원인 / 해결 |
|---|---|
| bootRun이 `Could not resolve placeholder 'POSTGRES_DB'` | `backend/`가 아닌 곳에서 실행했거나 `infra/.env`가 없음 |
| `docker compose up`이 `POSTGRES_PASSWORD 를 설정하세요` | `infra/.env`의 필수 값이 비어 있음 |
| DB 접속이 `password authentication failed` | 볼륨이 예전 비밀번호로 만들어짐. 로컬 데이터라면 `docker compose down -v` 후 다시 `up` |
| 화면 API가 전부 실패, Vite 터미널에 `http proxy error ... ECONNREFUSED` | 백엔드(8080)가 떠 있지 않음 |
| 로그인 시 "등록되지 않은 redirect_uri" | 제공자 콘솔에 `http://localhost:8080/login/oauth2/code/{provider}` 미등록 |
| 발급 시 503 "준비 중" | 해당 이벤트의 Redis 재고가 없음 → `POST /api/admin/coupons/{id}/stock/sync` (관리자 토큰) |
| PowerShell의 `curl`이 이상하게 동작 | PowerShell 5.1의 `curl`은 `Invoke-WebRequest` 별칭입니다. `curl.exe`를 사용하세요 |

## 폴더 구조

```
backend/   Spring Boot (com.couponrush.{auth, user, coupon, global}), Flyway 마이그레이션, Lua 스크립트
frontend/  React (src/{api, hooks, components, pages, auth, utils})
infra/     docker-compose.yml, .env.example
docs/      동시성·Redis·Kafka 설계와 측정 기록
```
