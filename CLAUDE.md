# Coupon Rush - 선착순 쿠폰/티켓팅 서비스

## 목표
대량 동시 요청에서도 정확히 선착순 발급이 되는 쿠폰/티켓팅 서비스.
Redis, 메시지큐, 소셜 로그인, LLM 기능을 단계적으로 적용하는 학습 겸 포트폴리오 프로젝트.

## 기술 스택
- Backend: Spring Boot 3.x, Java 21, Gradle, JPA
- DB: PostgreSQL (pgvector 포함)
- Cache/Lock: Redis
- Message Queue: Kafka
- Auth: Spring Security OAuth2 Client (카카오/네이버/구글) + JWT
- Frontend: React + Vite + TypeScript, TanStack Query
- Infra: Docker Compose (로컬), 추후 AWS EC2 배포
- LLM: Spring AI (나중 단계)

## 구조
- backend/ : Spring Boot 프로젝트
- frontend/ : React 프로젝트
- infra/ : docker-compose.yml 등 인프라 설정
- docs/ : 설계/실험 기록 (concurrency-notes.md: 동시성 재현·해결 리포트, 단계마다 결과 추가)

## 규칙
- 한 번에 한 기능만 구현하고, 구현 후 테스트 코드를 함께 작성한다.
- 쿠폰 발급 핵심 경로에는 LLM 호출을 절대 넣지 않는다.
- 비밀키, Client Secret은 코드에 넣지 않고 환경변수(.env)로 관리한다. .env는 .gitignore에 포함한다.
- 코드 변경 시 왜 그렇게 설계했는지 간단히 설명해준다.
- 소셜 로그인은 OAuth2UserInfo 추상화로 제공자 차이를 흡수한다.

## 개발 순서
1. 프로젝트 뼈대 + docker-compose (Postgres, Redis, Kafka)
2. 소셜 로그인 + JWT
3. 쿠폰 발급 API (DB 기반)
4. Redis로 재고 차감 개선 + 동시성 테스트
5. Kafka 비동기 처리
6. React 화면 연동
7. LLM 기능 (이벤트 문구 생성 → 자연어 검색 → 챗봇)

## 진행 상황
작업 단계가 끝날 때마다 이 섹션을 갱신하고 함께 커밋한다. (여러 PC에서 이어서 작업하기 위함)

- [x] 1. 프로젝트 뼈대 + docker-compose
- [x] 2. 소셜 로그인 + JWT
  - 카카오/네이버/구글 로그인, Flyway 도입, Access(30m)+Refresh(14d, Redis), refresh/logout API,
    로그아웃 블랙리스트, JWT 인증 필터, /api/users/me
- [x] 3. 쿠폰 발급 API (DB 기반) — 상세: docs/concurrency-notes.md
  - 쿠폰 생성/조회/발급/내 쿠폰 API, V2(coupons, coupon_issues), V3(coupons.version)
  - 순진한 구현으로 초과 발급 재현: 재고 100 / 1000명 → 907건 발급 (lost update)
  - DB 락 3방식 구현 + 비교 (CouponIssuer 전략. 3단계 당시 기본은 조건부 UPDATE, 4단계부터 REDIS). 재고 100 / 1000명은 세 방식 모두 정확히 100건.
    최대 경합(재고 1000 / 1000명) 평균: 조건부 UPDATE 2,413ms / 비관적 락 5,143ms / 낙관적 락 12,815ms(충돌 5,221회)
  - 결론: 선착순(경합 극심)에는 조건부 UPDATE. 공통 한계는 DB 한 행 직렬화 + 커넥션 풀 점유 → 4단계 Redis
- [x] 4. Redis로 재고 차감 개선 + 동시성 테스트 — 설계·키·비교표·정합성은 아래 "Redis 발급 (4단계)" 섹션
  - IssueStrategy.REDIS 추가, **API 기본 방식을 REDIS로 변경** (COUPON_ISSUE_STRATEGY로 DB 방식 선택 가능)
  - Lua 원자 판정, SUCCESS만 DB 저장, 보상 Lua, 생성 시 afterCommit 적재, sync API
  - 1,000명 / 10,000명 / 중복 요청 모두 초과·중복 발급 0. 10,000명에서 Redis가 DB 락 방식보다 7~11배 빠름
  - 측정 중 Lua 스크립트 로딩 성능 버그 발견·수정, 보상 정합성 검증, 보상 불가능한 경우의 선택지 정리(미구현)
  - (선택) HTTP 부하 테스트(k6 등)로 처리량 비교 — 테스트 회원/토큰 생성 스크립트 필요
- [ ] 5. Kafka 비동기 처리  ← 다음 작업 (Redis SUCCESS → 발급 이벤트 발행 → 컨슈머가 DB 저장/집계)
- [ ] 6. React 화면 연동
- [ ] 7. LLM 기능

## 결정 사항 / 메모

### 공통 / 인프라
- Spring Boot 3.5.16 고정: Spring Initializr가 4.x만 제공해서 Wrapper만 받고 build.gradle에서 수동 지정.
  Gradle Wrapper는 Boot 3.5 호환을 위해 8.14.3.
- 패키지: com.couponrush.{auth, user, coupon, global(config, error)}
- 이미지: pgvector/pgvector:pg17, redis:7.4-alpine, apache/kafka:3.9.1 (KRaft 단일 노드)
- 접속 정보/비밀값은 infra/.env 하나로 관리. docker-compose와 Spring(application.yml의
  spring.config.import: optional:file:../infra/.env[.properties])이 같은 파일을 읽는다. .env는 기동 시에만 읽음.
- 현재 시각은 Clock Bean(ClockConfig)으로 주입 → 테스트에서 시간 고정.
- 에러 응답은 {"code","message"} (global/error: ErrorCode, BusinessException, GlobalExceptionHandler).
  @Valid 실패·JSON 형식 오류·경로 타입 오류는 400 INVALID_INPUT.

### 테스트
- Testcontainers로 compose와 동일한 이미지를 띄워 검증한다 (Docker 실행 필요, 실행 중인 앱/로컬 DB와 무관).
- 통합 테스트는 @IntegrationTest 메타 애너테이션 사용 (더미 비밀값 고정 + 컨텍스트 캐시 공유).
- 테스트는 각자 만든 데이터를 @AfterEach에서 정리한다 (coupon_issues → coupons → users 순서, FK 때문. Redis는 event:*, refresh:*).
- 쿠폰 테스트에서 쿠폰을 만들 때는 ConcurrencyTestSupport.saveAndLoad/saveOpenCoupon으로 Redis에도 적재한다
  (IssueStrategy 파라미터 테스트에 REDIS가 포함되므로).

### DB / Flyway
- 스키마는 Flyway로만 변경한다 (backend/src/main/resources/db/migration/V{n}__설명.sql).
  ddl-auto: validate — 엔티티와 스키마가 다르면 기동 실패. 이미 적용된 마이그레이션 파일은 수정하지 않고 새 버전을 추가한다.
- enum(OAuth2Provider, Role)에 값을 추가하면 CHECK 제약(users_provider_check, users_role_check)을
  바꾸는 마이그레이션도 추가해야 한다. 빠뜨리면 FlywayMigrationTest가 실패한다.
- Flyway 도입 전(ddl-auto: update 시절)에 만들어진 로컬 DB는 "non-empty schema without schema history table"로
  기동이 실패한다. 로컬 테스트 데이터뿐이므로 DROP TABLE users; 후 재기동하면 V1부터 적용된다.

### 회원 / 소셜 로그인
- 회원 식별 키는 (provider, providerId). 닉네임은 바뀔 수 있어 식별에 쓰지 않고 로그인마다 갱신.
  이메일은 nullable. 테이블명 users ("user"는 PostgreSQL 예약어).
  같은 사람이 다른 제공자로 로그인하면 별도 회원 (이메일로 계정 자동 병합 안 함 — 계정 탈취 위험).
- 제공자 공통 규칙(기본 닉네임 "{registrationId}_{id}", 50자 자르기, 빈 이메일 무시)은 AbstractOAuth2UserInfo에.
  구현체는 응답에서 값을 꺼내는 위치만 정의. 새 제공자 = 구현체 + OAuth2Provider 값 + Factory switch 분기
  + CHECK 제약 마이그레이션.
- 구글은 scope에 openid를 넣지 않는다 (넣으면 OidcUserService로 빠져 CustomOAuth2UserService를 안 탐).
  구글 이메일은 email_verified=true일 때만 저장. 네이버는 user-name-attribute=response (중첩 응답).
- 콜백 URL: http://localhost:8080/login/oauth2/code/{kakao|naver|google} (각 콘솔에 등록)

### 인증 (JWT)
- Security: 기본은 전부 인증 필요. 공개 경로는 /actuator/health, POST /api/auth/refresh, GET /api/coupons/*.
  /api/admin/**는 ROLE_ADMIN. stateless, CSRF/폼로그인 off (OAuth2 state 보관에만 세션 잠깐 사용).
- JWT에는 userId(sub), token_type(access|refresh), jti만 담고 access에만 role 추가. 개인정보는 넣지 않는다.
  token_type으로 refresh를 Bearer로 쓰는 것(또는 반대)을 막는다.
- 토큰 전달: access는 URL fragment(#accessToken=) → 프론트 메모리 보관,
  refresh는 HttpOnly 쿠키(refresh_token, Path=/api/auth, SameSite=Strict, Secure는 AUTH_COOKIE_SECURE).
  배포 시 프론트/백엔드가 다른 사이트면 SameSite=None + Secure로 바꿔야 한다.
- Redis 키: refresh:{userId} → SHA-256(refresh token), TTL 14d (사용자당 1개, 재로그인 시 이전 기기 무효)
  blacklist:{jti} → 로그아웃된 access token, TTL = 남은 유효시간. JwtAuthenticationFilter가 매 요청 확인.
- refresh 시 role은 DB에서 다시 읽는다. refresh token rotation은 아직 안 함 (추후 선택).
- 인증 실패: 401은 JsonAuthenticationEntryPoint (UNAUTHORIZED / TOKEN_EXPIRED / TOKEN_INVALID / TOKEN_REVOKED /
  REFRESH_TOKEN_*), 403은 JsonAccessDeniedHandler (FORBIDDEN). 필터 자체는 응답하지 않고 사유만 request attribute에 남긴다.
- JwtAuthenticationFilter는 @Component로 등록하지 않는다 (서블릿 필터 자동 등록으로 두 번 실행되는 것 방지).

### 쿠폰
- API: GET /api/coupons/{id}(공개), POST /api/coupons/{id}/issue, GET /api/users/me/coupons,
  POST /api/admin/coupons(ROLE_ADMIN). 발급자는 토큰에서만 꺼낸다 (바디로 userId 받지 않음).
- 발급 방식은 coupon/issue/ 의 CouponIssuer 구현체 (REDIS 기본, CONDITIONAL_UPDATE, PESSIMISTIC_LOCK, OPTIMISTIC_LOCK).
  CouponIssueService가 app.coupon.issue.strategy(환경변수 COUPON_ISSUE_STRATEGY)로 골라 위임한다.
  공통 검증/저장(exists 빠른 실패, 기간·소진 검사, 유니크 위반 → COUPON_ALREADY_ISSUED)은 CouponIssueSupport.
  새 방식 = IssueStrategy 값 + CouponIssuer 구현체 (테스트·benchmark는 enum 기준으로 자동 포함).
- 트랜잭션은 각 CouponIssuer가 직접 관리한다. CouponIssueService.issue는 @Transactional이 아니다
  (낙관적 락은 재시도마다 TransactionTemplate으로 새 트랜잭션을 연다. 바깥 트랜잭션 안에서 호출하면 예외).
- Coupon.increaseIssuedQuantity()(메모리 +1)는 비관적 락(FOR UPDATE) 또는 @Version으로 보호될 때만 쓴다.
  단독 사용 = 3단계 순진한 구현(lost update). 조건부 UPDATE는 version도 함께 올린다 (방식이 섞여도 충돌 감지).
- 중복 발급 최종 판정은 uk_coupon_issues_coupon_user 유니크 제약, 예외로 롤백되며 차감도 취소.
  낙관적 락이 재시도(기본 100회)를 넘기면 COUPON_ISSUE_CONFLICT(409).
- 엔티티 Atomic 필드, @Transactional + synchronized, 인메모리 AtomicInteger는 해결책이 아니다
  (요청마다 엔티티 객체가 다름 / 커밋 전에 락 해제 / 서버 여러 대에서 깨짐). 이유는 docs/concurrency-notes.md.
- CouponIssue는 Coupon/User를 @ManyToOne 없이 ID로만 참조 (발급 경로 단순화, 4~5단계 쓰기 경로 교체 대비).
- 발급 기간은 [issueStartAt, issueEndAt) — 시작 포함, 종료 미포함.

### Redis 발급 (4단계, 기본 방식) — 상세: docs/concurrency-notes.md 4장
코드: coupon/issue/redis/ (RedisCouponIssuer, RedisCouponStock), 스크립트: resources/redis/*.lua

**설계 (발급 흐름)**
1. `coupon-issue.lua` 한 번 호출 (원자적, DB 접근 없음): 적재 여부 → 기간 → 중복(SISMEMBER) → 재고(GET) → DECR + SADD
   - 반환: SUCCESS / DUPLICATE(→409 ALREADY_ISSUED) / SOLD_OUT(→409) / NOT_IN_PERIOD(→400) / NOT_READY(→DB로 사유 구분: 404 또는 503 STOCK_NOT_READY)
2. SUCCESS일 때만 DB 트랜잭션(TransactionTemplate, Redis 호출 뒤에 짧게): coupon_issues INSERT → 조건부 UPDATE(issued_quantity +1)
3. DB가 실패하면 `coupon-issue-restore.lua`(INCR + SREM)로 보상. 유니크 위반이면 SREM은 안 함(DB에 이력이 있으므로).
- 재고 적재: 쿠폰 생성의 afterCommit에서 `coupon-stock-load.lua`(이벤트 오픈 전 미리). 롤백된 쿠폰이 Redis에 남지 않게 커밋 이후.
- 복구: POST /api/admin/coupons/{id}/stock/sync — DB 발급 이력 기준 재적재 (트래픽 없을 때만, 진행 중이면 커밋 전 발급이 사라짐).
- 규칙: 판정은 반드시 Lua 하나로 (GET 후 DECR 분리 금지 — check-then-act). 스크립트에는 O(1) 명령만.
  Lua 스크립트는 기동 시 문자열로 읽어 DefaultRedisScript(String, Class)로 만든다
  (ResourceScriptSource 금지: 호출마다 동기화된 파일 변경 확인 → 모든 요청 직렬화. 실측 10k회 12.1s → 2.9s).
- Redis 장애 시 DB 방식으로 자동 전환하지 않는다 (전환 순간 두 재고가 갈라짐). 발급 중단 + 복구 후 sync.

**키 구조** (중괄호는 Redis Cluster hash tag — 세 키를 같은 슬롯에 둬야 Lua 하나에서 다룰 수 있음, 만료 = 발급 종료 + 7일)

| 키 | 타입 | 내용 |
|---|---|---|
| `event:{couponId}:stock` | string | 남은 재고 |
| `event:{couponId}:issued` | set | 발급받은 userId (첫 SADD 때 생성, 스크립트가 재고 키와 같은 TTL 부여) |
| `event:{couponId}:meta` | hash | `start`, `end` (epoch ms) — 발급 경로에서 DB를 읽지 않기 위해 추가 |

**3방식 성능 비교** (재고 100, 32스레드, 로컬 Windows + Docker Desktop, 같은 실행에서 측정, 모든 실행 초과·중복 발급 0)

| 방식 | 1,000명 | 10,000명 | 1,000명 × 3회 | 최대 경합 (재고 1000 / 1000명) |
|---|---:|---:|---:|---:|
| 비관적 락 | 5,280 ms | 26,577 ms | 9,021 ms | 5,896 ms |
| 낙관적 락 | 4,428 ms | 19,132 ms | 6,346 ms | 19,232 ms (충돌 5,360회) |
| **Redis** | **684 ms** | **2,387 ms** | **644 ms** | **3,234 ms** |
| (참고) 조건부 UPDATE | 2,563 ms | 16,866 ms | 4,906 ms | 6,217 ms |

- 요청이 재고보다 많을수록 Redis가 유리 (실패 요청이 DB에 안 감). 10,000명에서 비관적 대비 약 11배, 낙관적 대비 약 8배.
- 최대 경합(전원 성공)에서는 약 2배 — 성공 요청의 **동기 DB 쓰기**가 남은 병목 → 5단계에서 비동기화.

**정합성 이슈와 해결**
- 불변식: `Redis 남은 재고 + DB 발급 행 = 총 수량`, `Redis issued Set = DB 발급 userId 집합`.
  새 기능을 넣을 때 이 두 식이 깨지지 않는지 테스트로 확인한다 (RedisCompensationConcurrencyTest).

| 이슈 | 결과 | 현재 해결 |
|---|---|---|
| Redis 성공 후 DB 저장 실패 | 재고 -1, 발급 없음 | 보상 Lua(INCR + SREM), 원자적. 정상 300 + 실패 700 동시에서도 불변식 유지 검증 |
| 보상 중 "거짓 소진" | 실패 요청이 재고를 잠깐 쥐는 동안 다른 사람이 SOLD_OUT | 데이터는 맞음. DB 실패가 드물면 허용 (완화: 남은 재고 있으면 1회 재시도) |
| Redis Set 유실 후 같은 사람 재요청 | DB 유니크 위반 | 재고만 되돌리고 Set 유지, ALREADY_ISSUED |
| Redis와 DB 카운터 불일치 | 조건부 UPDATE 0행 | 예외 → 롤백(INSERT 포함) + 보상 (DB 제약이 이중 안전망) |
| 보상 실패 (Redis 순간 장애) | 덜 발급(안전한 방향) | 에러 로그 + sync API |
| Redis 데이터 유실 / 미적재 | NOT_READY → 503 | sync API로 DB 기준 재적재 |
| **Lua 성공 직후 서버 종료** (보상 불가) | 유령 발급: 재고 -1, Set에 있지만 DB 없음 | **미구현**. 선택지: 대조 배치(ZSET 시각 필요) / pending + 워커 / Lua 안 XADD(Stream) / Kafka+아웃박스 / 사후 대조. 5단계 설계 시 C(Stream) vs B+D(pending+Kafka) 결정 |
| DB 타임아웃 후 보상(실제로는 커밋됨) | 재고가 하나 더 풀림 | DB CHECK·조건부 UPDATE가 초과 발급을 최종 차단 |

## 자주 쓰는 명령 (PowerShell 기준)
- 전체 테스트: `cd backend; .\gradlew test`
- 동시성 테스트만: `.\gradlew test --tests "com.couponrush.coupon.CouponIssueConcurrencyTest" --rerun`
  → 결과 숫자: `build\reports\tests\test\classes\com.couponrush.coupon.CouponIssueConcurrencyTest.html` 의 Standard output
- 방식별 성능 비교: `.\gradlew benchmark` (@Tag("benchmark"), 기본 test에서 제외, 약 8분)
  → `build\benchmark\coupon-issue-strategies.md` (재고 100/1000), `build\benchmark\coupon-issue-scale.md` (1k/10k/중복 요청)
- 실제 서버에서 방식 바꾸기: `.env`에 `COUPON_ISSUE_STRATEGY=redis` 등 → bootRun 재시작
  (진행 중인 쿠폰은 바꾼 뒤 `POST /api/admin/coupons/{id}/stock/sync`)
- Redis 재고 보기: `docker exec coupon-rush-redis redis-cli GET "event:{1}:stock"` / `SCARD "event:{1}:issued"`
- 기본 방식이 REDIS라서, Redis 적재 기능 이전(4단계 전)에 만든 로컬 쿠폰은 발급 시 503 → `POST /api/admin/coupons/{id}/stock/sync` 한 번
- curl은 `curl.exe`로 (PowerShell 5.1의 curl은 Invoke-WebRequest 별칭). JSON 바디는 파일로:
  `[IO.File]::WriteAllText("$PWD\body.json", $json)` 후 `--data-binary "@body.json"`
- 로컬 관리자 만들기: `UPDATE users SET role='ADMIN' WHERE id=?;` → `/api/auth/refresh`로 새 access token (role은 DB에서 다시 읽음)
- 로컬 쿠폰 초기화: `TRUNCATE coupon_issues, coupons RESTART IDENTITY;`
- psql 접속: `docker exec -it coupon-rush-postgres psql -U coupon -d coupon_rush`
- Redis 키 보기: `docker exec coupon-rush-redis redis-cli --scan --pattern "refresh:*"`

## 새 PC에서 시작하기
1. Java 21, Docker Desktop, Git 설치 (Gradle은 gradlew가 받음)
2. git clone https://github.com/lsj199608/coupon-rush.git
3. infra/.env.example → infra/.env 복사 후 값 채우기 (.env는 git에 없음)
4. git config --global user.name / user.email 설정
5. 확인: cd infra && docker compose up -d → cd ../backend && ./gradlew test
6. 기존 로컬 DB가 Flyway 도입 전 것이면 위 "DB / Flyway" 메모대로 정리
