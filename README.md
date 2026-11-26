# Coupon Rush

대량 동시 요청에서도 정확히 선착순으로 발급되는 쿠폰/티켓팅 서비스입니다.
Redis(원자적 재고 차감), Kafka(비동기 발급), 소셜 로그인(카카오·네이버·구글) + JWT, React 화면을 단계적으로 적용한 학습 프로젝트입니다.

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
                                                                               │ llm-requests / llm-results
                                                                   llm-service :8081 (LLM 호출 전담, 공개 API 없음)
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
| coupon-rush-kafka | 9092 (호스트용), 29092 (컨테이너끼리, 호스트에 열지 않음) |
| coupon-rush-llm-service | 없음 — `--profile llm` 일 때만 (아래 2-1) |

데이터는 Docker 볼륨에 남습니다. 끄기는 `docker compose down`, 데이터까지 지우려면 `docker compose down -v`입니다.

### 2. 백엔드: Spring Boot (8080)

```powershell
cd backend
.\gradlew :coupon-api:bootRun
```

- `backend/`는 Gradle 멀티모듈 루트입니다. `:coupon-api:bootRun`은 `backend/coupon-api` 폴더에서 실행되어 `../../infra/.env`를 읽습니다. **명령은 `backend/` 폴더에서** 입력합니다.
- 처음 기동할 때 Flyway가 스키마를 만들고, Kafka 토픽(`coupon-issue-requests`, `.DLT`)이 자동으로 생깁니다.
- 확인: `curl.exe http://localhost:8080/actuator/health` → `{"status":"UP"}`

### 2-1. (선택) LLM 서비스: llm-service (8081)

관리자 **이벤트 문구 생성**·챗봇을 쓸 때만 띄웁니다. 없어도 다른 기능은 모두 동작하고, 문구 생성·챗봇 질문만 60초 뒤 실패(TIMEOUT)합니다
(자연어 검색은 15초 뒤 키워드 검색으로 대신합니다).
기본은 **fake 제공자**(API 키·비용 없음, 입력으로 정해진 문구 3개)입니다. 실제 Claude를 쓰려면 `infra/.env`에
`LLM_PROVIDER=anthropic`과 `LLM_API_KEY=...`를 넣고 다시 띄웁니다 (호출마다 비용이 듭니다).

**fake와 실제(anthropic)의 차이** — fake는 LLM을 부르지 않고 **미리 만들어 둔 코드 규칙**으로 결과를 만듭니다.
요청 → Kafka → 캐시·예산 → 결과 발행까지의 흐름은 똑같이 실제로 동작하고, "결과를 누가 만드느냐"만 다릅니다.
그래서 fake 결과는 품질 확인용이 아니라 **흐름 확인용**입니다. fake가 아니면(anthropic + 키) Claude가 정상적으로 문장을 이해해 결과를 만듭니다.

| 기능 | fake (기본) — 결과를 만드는 코드 | anthropic — Claude 에게 주는 것 / 결과를 검사하는 코드 |
|---|---|---|
| 이벤트 문구 생성 | `FakeLlmClient.generateEventCopy` — 톤별 고정 문장에 키워드·수량·기간을 끼워 넣음 | `prompts/event-copy.v1.txt` + `EventCopyOutput` / `EventCopyOutputValidator` |
| 자연어 검색 | `FakeEventSearch` — 정해진 표현만 인식("진행 중", "남은", "이번 주 끝나는" 등), 나머지 단어 → keywords. 처음 보는 표현은 이해하지 못함 | `prompts/event-search.v1.txt` + `EventSearchOutput` / `EventSearchOutputValidator` |
| 챗봇 | `chatbot/model/FakeChatbotModel` — 정해진 말("내 쿠폰", "3번 이벤트", "발급해줘" …)로 도구를 하나 골라 mcp-server로 실제 조회하고, 결과를 틀에 넣어 답함. 앞 대화는 이해하지 못함 | `prompts/chatbot.v1.txt` + mcp-server 도구 목록 → Claude tool use (`client/anthropic/AnthropicChatbotModel`) |

(파일 위치: `backend/llm-service/src/main/java/com/couponrush/llmservice/client/`, 프롬프트는 `.../src/main/resources/prompts/`)
어느 제공자든 결과는 같은 계약 형식으로 나가므로 coupon-api는 차이를 모릅니다. 기동 로그의 `LLM 제공자: fake` / `anthropic`으로 어느 쪽인지 확인할 수 있습니다.

**챗봇을 쓰려면** mcp-server(8082)도 띄우고, `infra/.env`에 `MCP_SERVICE_TOKEN`·`INTERNAL_API_TOKEN`을 넣어야 합니다
(챗봇 → mcp-server → coupon-api 구간 인증. 없으면 챗봇 질문만 실패합니다). 지금은 **호스트 bootRun(B)에서만** 챗봇 도구가 동작합니다 —
mcp-server가 루프백(127.0.0.1)에서만 접속을 받아 컨테이너(A)에서는 닿지 않습니다.

두 가지 방법 중 **하나만** 씁니다 (둘 다 켜면 같은 컨슈머 그룹을 나눠 가져서 로그를 따라가기 어렵습니다).

**A. 컨테이너로 (compose `llm` 프로필)** — 코드를 고치지 않을 때

```powershell
cd infra
docker compose --profile llm up -d --build   # 처음엔 이미지 빌드로 몇 분. 이후 코드가 바뀌면 --build 를 다시
docker compose --profile llm ps              # coupon-rush-llm-service 가 running (healthy)
docker logs -f coupon-rush-llm-service       # "LLM 제공자: fake" 가 보이면 정상
```

- 기본 `docker compose up -d`에는 포함되지 않습니다(프로필). 끄기: `docker compose --profile llm stop llm-service`
- 컨테이너에는 `.env`를 통째로 넘기지 않고 `LLM_*`와 Kafka·Redis 주소만 넘깁니다 (JWT·OAuth·DB 비밀값은 받지 않음).
- 포트를 열지 않습니다 (공개 API 없음). 컨테이너 안에서는 Kafka에 `kafka:29092`로 접속합니다.

**B. 호스트에서 (bootRun)** — llm-service 코드를 고치며 볼 때

```powershell
cd backend
.\gradlew :llm-service:bootRun      # 다른 터미널에서. coupon-api 와 같은 infra/.env 를 읽음
```

- 공개 API가 없습니다. coupon-api와 Kafka(`llm-requests` → `llm-results`)로만 통신하고, 8081은 헬스체크(`/actuator/health`)용입니다.

### 2-2. (선택) MCP 서버: mcp-server (8082)

AI 클라이언트(Claude Code 등)가 쿠폰 이벤트를 **조회 전용 도구**로 쓰게 해 주는 서버입니다. coupon-api(8080)의 공개 조회 API를 부릅니다.

```powershell
cd backend
.\gradlew :mcp-server:bootRun      # → http://localhost:8082/mcp (MCP Streamable HTTP)
```

| 도구 | 하는 일 |
|---|---|
| `search_events` | 자연어 질문으로 이벤트 검색 (coupon-api 자연어 검색, AI를 못 쓰면 키워드 검색 결과) |
| `get_event` | 이벤트 하나의 기간·상태·남은 수량 |
| `get_my_coupons` | **로그인한 본인**이 받은 쿠폰 (인자 없음 — 누구인지는 연결 인증으로만 정해짐) |

- 발급 도구는 없습니다. 결과의 `url`(이벤트 페이지)에서 사용자가 직접 발급합니다.
- 이 PC(127.0.0.1)에서만 접속됩니다. 허용한 Host 헤더(`localhost:8082`, `127.0.0.1:8082`)가 아니면 421로 거절합니다 (DNS 리바인딩 방지).

**인증** — 검색·상세는 인증 없이 됩니다. `get_my_coupons`는 로그인이 필요합니다.

| 누가 | 보내는 헤더 | mcp-server가 하는 일 |
|---|---|---|
| 사람 (Claude Code 등) | `Authorization: Bearer <coupon-api access token>` | 토큰을 coupon-api `GET /api/users/me/coupons`에 그대로 전달 (검증·만료 판정은 coupon-api) |
| 서버 (C2 챗봇, llm-service) | `X-Mcp-Service-Token` + `X-Mcp-User-Id` | 서비스 토큰 확인 → coupon-api 내부 API `GET /internal/users/{id}/coupons` (`X-Internal-Token`) |

Claude Code에서 내 쿠폰까지 쓰려면 access token(30분)을 연결 설정에 넣습니다.
1. 브라우저(5173)에서 로그인 → 개발자 도구(F12) **네트워크** 탭에서 `refresh` 요청의 응답 `accessToken` 값을 복사
2. `claude mcp add --transport http coupon-rush http://localhost:8082/mcp --header "Authorization: Bearer <복사한 값>"`
   (이미 추가했다면 `claude mcp remove coupon-rush` 후 다시 추가). 30분이 지나면 도구가 "로그인이 필요합니다"라고 알려 주니 같은 방법으로 바꿉니다.
- 서비스 인증은 `infra/.env`의 `MCP_SERVICE_TOKEN`, `INTERNAL_API_TOKEN`이 있을 때만 켜집니다 (없으면 그 경로만 꺼지고, 사람의 Bearer 경로는 그대로 동작).

### 2-3. (선택, 개발 중) 채팅 서버: web-chat-service (8083)

같은 이벤트 페이지를 보는 사람끼리 대화하는 WebSocket(STOMP) 서버입니다. Redis(`REDIS_HOST`·`REDIS_PORT`, coupon-api와 같은 값)와
자기 데이터베이스 `web_chat`(같은 Postgres 서버, 전용 계정 — `WEB_CHAT_DB_PASSWORD`)이 필요합니다. coupon-api의 `coupon_rush`는 쓰지 않습니다.

**처음 한 번: `web_chat` 데이터베이스 만들기.** `infra/.env`에 `WEB_CHAT_DB_PASSWORD`를 넣고 `docker compose up -d`를 다시 실행합니다.
빈 볼륨으로 처음 만드는 Postgres라면 초기화 스크립트(`infra/postgres/20-web-chat-db.sh`)가 자동으로 만들어 줍니다.
**지금까지 쓰던 볼륨이면 초기화 스크립트가 돌지 않으므로** 같은 스크립트를 직접 한 번 실행합니다 (여러 번 실행해도 됨):

```powershell
cd infra
docker compose up -d     # 새 환경변수·스크립트를 반영해 postgres 컨테이너를 다시 만든다 (데이터 볼륨은 그대로)
docker exec coupon-rush-postgres sh /docker-entrypoint-initdb.d/20-web-chat-db.sh   # → "web_chat 데이터베이스·계정 준비 완료"
```

테이블은 web-chat-service가 기동할 때 자기 Flyway로 만듭니다.

```powershell
cd backend
.\gradlew :web-chat-service:bootRun    # → ws://localhost:8083/ws/web-chat
```

- 방 = 이벤트: 구독 `/topic/events/{id}`, 보내기 `/app/events/{id}/chat` (`{"content": "..."}`, 500자), 내 메시지 거절 사유 `/user/queue/errors`.
- **보내려면 로그인 연결**: coupon-api `POST /api/web-chat/tickets`(로그인)로 1회용 티켓(30초)을 받아 STOMP CONNECT의 `ticket` 헤더에 넣습니다.
  티켓 없이 연결하면 읽기만 되고, 만료·재사용된 티켓은 연결이 거절됩니다. 다시 연결할 때마다 새 티켓을 받습니다. 화면(이벤트 상세의 채팅)은 이 과정을 알아서 합니다.
- 보낸 사람·닉네임은 서버가 티켓으로 정하고, 회원마다 10초에 5개까지 보낼 수 있습니다. 허용한 Origin(`http://localhost:5173`)의 브라우저만 연결됩니다.
- 보낸 메시지는 방에 먼저 전달된 뒤 모아서 저장됩니다(0.2초 간격). DB가 멈춰도 대화는 계속되고, 그동안의 메시지는 지난 대화에만 늦게 나타나거나(재시도) 빠집니다.
- 지난 대화: `GET http://localhost:8083/api/web-chat/rooms/{이벤트 ID}/messages` (공개) → 최근 50개(오래된 것부터) + `nextBeforeId`.
  더 이전은 `?beforeId={nextBeforeId}`, 개수는 `?size=1~100`.
- **여러 대로 띄울 수 있습니다**: 메시지는 Redis 채널 `web-chat:messages`로 모든 서버에 전달되어, 서로 다른 서버에 붙은 사람끼리 대화하고 순서도 같게 보입니다.
  로컬에서 두 대: 다른 창에서 `.\gradlew :web-chat-service:bootRun --args="--server.port=8084"`. Redis가 멈추면 보내기가 거절됩니다(`WEB_CHAT_UNAVAILABLE`, 연결은 유지).
  도배 제한도 Redis에서 세므로, 탭이 서로 다른 서버에 붙어도 회원 한 명의 한도는 그대로입니다.

### 3. 프론트엔드: Vite (5173)

```powershell
cd frontend
npm install              # 처음 한 번, package.json 이 바뀌었을 때
npm run dev
```

http://localhost:5173 에 접속합니다. 프론트엔드에는 환경변수가 없습니다. API 요청은 Vite 프록시가 8080으로 넘기므로 백엔드가 먼저 떠 있어야 합니다.
이벤트 상세 화면의 채팅은 채팅 서버(8083, [2-3](#2-3-선택-개발-중-채팅-서버-web-chat-service-8083))로 넘깁니다(`/ws/web-chat`, `/api/web-chat/rooms`).
채팅 서버를 띄우지 않으면 채팅 칸에 "다시 연결하는 중"만 보이고, 나머지 화면은 그대로 동작합니다.

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
| `WEB_CHAT_DB_PASSWORD` | compose, web-chat-service | 채팅 데이터베이스 `web_chat` 전용 계정 비밀번호 (D6). Postgres를 **처음 만들 때** 초기화 스크립트가 계정을 만들고, 기존 볼륨이면 [2-3](#2-3-선택-개발-중-채팅-서버-web-chat-service-8083)의 스크립트를 직접 실행. `POSTGRES_PASSWORD`와 다르게 |
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
| `WEB_CHAT_DB_USER` | `web_chat` | 채팅 데이터베이스 전용 계정 이름 (데이터베이스 이름은 `web_chat` 고정) |
| `REDIS_HOST` | `localhost` | Redis 호스트 (coupon-api·llm-service·web-chat-service가 같은 Redis) |
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
| `LLM_REQUEST_TIMEOUT` | `60s` | LLM 문구 생성 요청 마감. 이 안에 결과가 없으면 실패(TIMEOUT) |
| `LLM_RATE_LIMIT_PER_MINUTE` | `5` | 관리자 1명의 분당 LLM 요청 한도 (넘으면 429) |
| `LLM_RATE_LIMIT_PER_DAY` | `50` | 관리자 1명의 일일 LLM 요청 한도 (한국 자정 초기화) |
| `LLM_PROVIDER` | `fake` | llm-service의 LLM 제공자: `fake`(키·비용 없음) \| `anthropic`. `anthropic`이어도 `LLM_API_KEY`가 비면 fake로 동작 |
| `LLM_API_KEY` | (없음) | Anthropic API 키. **비밀값**, llm-service만 읽음 |
| `LLM_MODEL` | `claude-sonnet-5-5` | 모델 ID (예: `claude-haiku-5-5`, `claude-opus-5-5`) |
| `LLM_DAILY_TOKEN_BUDGET` | `50000` | 서비스 전체 하루 토큰(입력+출력) 예산, 한국 자정 초기화. 넘으면 문구 생성 요청을 접수 단계에서 429로 거절 |
| `MCP_SERVICE_TOKEN` | (없음 = 꺼짐) | 챗봇 서버(llm-service) → mcp-server 서비스 인증 값(`X-Mcp-Service-Token`). **비밀값**, mcp-server·llm-service가 같은 값을 읽음. 없으면 챗봇 질문이 실패(LLM_UNAVAILABLE). 사람이 Claude Code로 쓸 때는 필요 없음 |
| `MCP_SERVER_URL` | `http://localhost:8082/mcp` | 챗봇(llm-service)이 도구를 부르는 mcp-server 주소 |
| `INTERNAL_API_TOKEN` | (없음 = 꺼짐) | mcp-server → coupon-api 내부 API(`/internal/**`) 인증 값(`X-Internal-Token`). **비밀값**, coupon-api·mcp-server가 같은 값을 읽음. `MCP_SERVICE_TOKEN`과 다르게 |

`.env`는 기동할 때만 읽습니다. 값을 바꾸면 `bootRun`(포트·DB 값이면 `docker compose`도)을 다시 시작하세요.

## 테스트

```powershell
cd backend;  .\gradlew test        # 모든 모듈. Testcontainers 로 Postgres/Redis/Kafka 를 따로 띄움 (Docker 실행 필요, .env 불필요)
cd frontend; npm test              # Vitest + React Testing Library
cd frontend; npm run build         # 타입 검사 + 프로덕션 빌드
cd backend;  .\gradlew :coupon-api:benchmark   # (선택) 방식별 성능 측정, 약 12분 → coupon-api/build/benchmark/*.md
```

## 서비스 이미지 빌드 (8단계 Kubernetes용)

저장소 루트에서 실행합니다. 백엔드는 빌드 컨텍스트가 `backend/`(멀티모듈 루트)이고, 이미지 안에서 Gradle로 bootJar만 만듭니다(테스트는 위 명령으로 따로).

```powershell
foreach ($m in 'coupon-api','llm-service','mcp-server','web-chat-service') {
  docker build -f "backend/$m/Dockerfile" -t "coupon-rush/${m}:local" backend
}
docker build -t coupon-rush/frontend:local frontend   # npm run build 결과를 담은 Nginx (8080, 비root)
```

모든 이미지는 root가 아닌 사용자로 실행됩니다(백엔드 uid 10001, 프론트 nginx uid 101). Docker Desktop Kubernetes(kubeadm 방식)는 로컬 이미지를 그대로 씁니다(저장소에 push 불필요).

## 문제 해결

| 증상 | 원인 / 해결 |
|---|---|
| bootRun이 `Could not resolve placeholder 'POSTGRES_DB'` | `infra/.env`가 없음, 또는 `:coupon-api:bootRun`이 아닌 다른 작업 폴더에서 실행함 (`.env`를 `backend/coupon-api` 기준 `../../infra/.env`로 찾음) |
| `docker compose up`이 `POSTGRES_PASSWORD 를 설정하세요` (또는 `WEB_CHAT_DB_PASSWORD`) | `infra/.env`의 필수 값이 비어 있음 |
| web-chat-service 기동이 `database "web_chat" does not exist` 또는 `password authentication failed for user "web_chat"` | 기존 볼륨이라 초기화 스크립트가 안 돎 → [2-3](#2-3-선택-개발-중-채팅-서버-web-chat-service-8083)의 `20-web-chat-db.sh` 직접 실행. 비밀번호를 나중에 바꿨다면 스크립트는 기존 계정 비밀번호를 바꾸지 않으므로 `ALTER ROLE web_chat PASSWORD '...'` |
| DB 접속이 `password authentication failed` | 볼륨이 예전 비밀번호로 만들어짐. 로컬 데이터라면 `docker compose down -v` 후 다시 `up` |
| 화면 API가 전부 실패, Vite 터미널에 `http proxy error ... ECONNREFUSED` | 백엔드(8080)가 떠 있지 않음 |
| 로그인 시 "등록되지 않은 redirect_uri" | 제공자 콘솔에 `http://localhost:8080/login/oauth2/code/{provider}` 미등록 |
| 발급 시 503 "준비 중" | 해당 이벤트의 Redis 재고가 없음 → `POST /api/admin/coupons/{id}/stock/sync` (관리자 토큰) |
| PowerShell의 `curl`이 이상하게 동작 | PowerShell 5.1의 `curl`은 `Invoke-WebRequest` 별칭입니다. `curl.exe`를 사용하세요 |

## 폴더 구조

```
backend/   Gradle 멀티모듈 루트
  coupon-api/   Spring Boot (com.couponrush.{auth, user, coupon, llm, search, chatbot, global}), Flyway 마이그레이션, Lua 스크립트
  llm-service/  LLM 호출 전담 Spring Boot (Kafka 컨슈머, DB 없음)
  mcp-server/   AI 클라이언트용 MCP 도구 서버 (coupon-api 조회 API 어댑터, DB 없음)
  web-chat-service/ 이벤트별 실시간 채팅방 (WebSocket + STOMP, 자기 DB web_chat — 대화 저장)
  libs/llm-contract/  두 서비스가 주고받는 Kafka 메시지 계약
  libs/web-chat-contract/  coupon-api ↔ web-chat-service 채팅 연결 티켓 계약 (Redis 키·값 모양)
frontend/  React (src/{api, hooks, components, pages, auth, utils}), Dockerfile + nginx/ (이미지용 Nginx 설정)
infra/     docker-compose.yml, .env.example, postgres/ (초기화 스크립트: pgvector, web_chat 데이터베이스)
docs/      동시성·Redis·Kafka 설계와 측정 기록
```
