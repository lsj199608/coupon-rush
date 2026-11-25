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
- Infra: Docker Compose (로컬), 8단계에서 AWS + Kubernetes 배포 (방식 미정 — 아래 진행 상황 8)
- LLM: Anthropic 공식 Java SDK (com.anthropic:anthropic-java, llm-service에만). 처음엔 Spring AI 예정이었으나 7단계에서 변경 —
  구조화된 출력·거절(stop_reason=refusal)·사용 토큰·상태코드별 예외를 SDK가 그대로 제공해 재시도·서킷·예산 판정이 정확해서

## 구조
- backend/ : Gradle 멀티모듈 루트 (gradlew 위치). coupon-api/ = 기존 Spring Boot 앱 전체, (7단계) llm-service/, libs/llm-contract/, mcp-server/, web-chat-service/
- frontend/ : React 프로젝트
- infra/ : docker-compose.yml 등 인프라 설정
- docs/ : 설계/실험 기록 (concurrency-notes.md: 동시성 재현·해결 리포트, 단계마다 결과 추가)

## 규칙
- 한 번에 한 기능만 구현하고, 구현 후 테스트 코드를 함께 작성한다.
- 쿠폰 발급 핵심 경로에는 LLM 호출을 절대 넣지 않는다.
- 비밀키, Client Secret은 코드에 넣지 않고 환경변수(.env)로 관리한다. .env는 .gitignore에 포함한다.
  새 환경변수를 추가하면 infra/.env.example과 README.md 환경변수 표도 함께 갱신한다.
- 코드 변경 시 왜 그렇게 설계했는지 간단히 설명해준다.
- 소셜 로그인은 OAuth2UserInfo 추상화로 제공자 차이를 흡수한다.

## 개발 순서
1. 프로젝트 뼈대 + docker-compose (Postgres, Redis, Kafka)
2. 소셜 로그인 + JWT
3. 쿠폰 발급 API (DB 기반)
4. Redis로 재고 차감 개선 + 동시성 테스트
5. Kafka 비동기 처리
6. React 화면 연동
7. LLM 기능 (이벤트 문구 생성 → 자연어 검색 → 챗봇) + WebSocket 채팅방(D)
8. AWS + Kubernetes 배포 (2026-11-24 순서 변경: Spring Batch보다 먼저)
9. Spring Batch (종료된 이벤트 결과 집계)
10. Postgres만 AWS RDS로 옮기기 (2026-11-24 결정, Spring Batch 다음 — 마지막)

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
- [x] 5. Kafka 비동기 처리 — 설계·토픽·키·202 흐름·멱등/재시도/DLT·보상·한계·측정은 아래 "Kafka 비동기 발급 (5단계)" 섹션
  - [x] 기본 파이프라인: Lua SUCCESS → PENDING(Redis) → Kafka(acks=all, idempotence, key=eventId, 파티션 3) → 202 + requestId
        → 컨슈머가 CouponIssue + IssueRequest(COMPLETED/FAILED) 저장, GET /api/issue-requests/{id}. mode=async 기본, sync 유지
  - 결과: 정확성 OK(재고 100/1000명, 1000/1000명 모두 초과·중복 0). 첫 측정(워밍업 없음)은 접수 3.4s, DB 반영 30s
    → 워밍업 후 재측정은 아래 "성능 측정" 항목
  - [x] 컨슈머 신뢰성: 멱등(유니크 위반 = 중복이면 정상), 지수 백오프 재시도(0.5s→1s→2s), DLT(coupon-issue-requests.DLT)
        + DLT 컨슈머가 FAILED(RETRY_EXHAUSTED) + 보상, DB 저장 성공 후에만 오프셋 커밋 — 실제 DB 장애·실제 커밋 오프셋으로 검증
  - [x] Redis ↔ DB 정합성(docs 6장): 보상 Lua 멱등화(restored Set, requestId 기준 1회), 발행 실패로 취소된 요청은 컨슈머가
        발급하지 않음(PUBLISH_CANCELLED), 대조 점검(관리자 API + 10분 스케줄러, 불일치만 WARN), 남은 한계 문서화
  - [x] 성능 측정(docs 7장, AsyncIssueBenchmarkTest, 워밍업 1회 후 1회 측정): 재고 1000 / 1000명 기준
        응답 p99 동기 178ms → 비동기 27ms, 모든 응답 2.5s → 0.6s / DB 반영 완료 2.5s → 12.0s (컨슈머 건당 처리가 병목)
        파티션·컨커런시: 이벤트 1개는 늘려도 그대로(키 = 쿠폰 ID → 한 파티션), 이벤트 6개 동시는 3/1 26s → 3/3 14s → 6/6 7.3s.
        완료 시간은 가장 많이 몰린 파티션이 결정(해시 충돌 주의), 파티션당 약 50건/s
  - 추가 개선 후보 (5단계 범위 밖, 필요할 때): **배치 컨슈머**(묶음 INSERT + issued_quantity +N + 커밋 1회 — 이벤트 1개의
    DB 반영 속도를 올리는 유일한 방법), Lua 안 XADD/PENDING으로 유령 발급 구간 제거, 오래된 PENDING 대조 워커, PENDING 기록 1왕복화
- [x] 6. React 화면 연동 — 아래 "프론트엔드 (6단계)" 섹션
  - [x] 프로젝트 세팅 + 로그인: Vite + React + TS, React Router, TanStack Query, axios. Vite 프록시(/api, /oauth2, /login/oauth2 → 8080)
        카카오/네이버/구글 로그인, AuthContext + ProtectedRoute, 헤더(닉네임·로그아웃), axios 인터셉터(Bearer 첨부, 401 → refresh → 재시도)
        백엔드: 로그인 성공 리다이렉트에서 access token 제거(URL에 토큰 없음) → 프론트가 refresh 쿠키로 access 획득
  - [x] 이벤트 목록(상태 뱃지) / 상세(기간·남은 수량·서버 시각 기준 카운트다운) / 발급(202 → 1초 폴링, 결과별 UI) / 내 쿠폰
        백엔드: GET /api/coupons 목록 추가, 응답에 status·serverTime, 남은 수량은 Redis 기준
  - [x] 관리자 이벤트 생성 폼(검증 + 생성 직후 Redis 재고 적재 확인), 모바일 반응형, 에러 바운더리, 404, 주요 흐름 테스트
  - [x] 실제 브라우저 수동 확인(로그인·발급·관리자·모바일 폭) 2026-11-07 — docs/manual-check.md 기록표
  - (선택, 미실시) Playwright E2E
- [ ] 7. LLM 기능  ← 첫 기능(이벤트 문구 생성) 완료, 다음: 자연어 검색 → 챗봇. 설계: LLM은 별도 서비스(llm-service)로 분리, coupon-api ↔ llm-service는 Kafka 요청/응답,
      DB는 coupon-api만(llm_requests), llm-service는 공개 포트 없음·LLM 키 단독 보유. Anthropic 공식 Java SDK, 기본·테스트는 fake 제공자
      (키 없으면 자동 fake). 레이트 리밋 사용자당 분 5회 / 일 50회, 마감 60초, 일일 토큰 예산 50,000(초과 시 거절, 사용량 Redis 기록).
      첫 기능: 관리자 이벤트 문구 생성 → 관리자가 확인·수정 후 저장 (V6 coupons.description, nullable)
  - [x] 0) Gradle 멀티모듈 전환: backend/src → backend/coupon-api/src (동작 변화 없음, 기존 테스트 전부 통과)
  - [x] 1) 메시지 계약 모듈 :llm-contract (폴더 libs/llm-contract, com.couponrush.llm.contract) — 아래 "LLM 메시지 계약" 메모
  - [x] 2) coupon-api 쪽 (com.couponrush.llm): V5 llm_requests, POST /api/admin/llm/event-copy(202), GET /api/admin/llm/requests/{id},
        결과 컨슈머(전용 그룹·컨테이너), 타임아웃(조회 시 즉시 + 30초 스케줄러), 레이트 리밋 Lua — 아래 "LLM 요청 (coupon-api)" 메모
  - [x] 3) llm-service 뼈대 + fake 제공자 (com.couponrush.llmservice) — 아래 "llm-service" 메모.
        실제 실행 확인: bootRun 2개 + curl로 202 → PENDING → 약 1초 후 COMPLETED(후보 3개),
        llm-service를 끄면 60초 후 TIMEOUT(그동안 GET /api/coupons 정상), 다시 켜면 남은 요청을 "마감 지남"으로 건너뜀(LLM 호출 0)
  - [x] 4) 실제 LLM: AnthropicLlmClient(공식 SDK 3.2.0, 기본 모델 claude-sonnet-5-5, effort low), 키 없으면 자동 fake,
        SDK 재시도 + Resilience4j 서킷 브레이커, 구조화된 출력 + 개수·길이 검증(1회 재생성), 거절 처리 — 아래 "Anthropic 제공자" 메모.
        테스트는 WireMock만 (실제 API 호출 없음 — 실제 키로 확인은 아직 안 함)
  - [x] 5) 비용 통제: 응답 캐시(24h) + 일일 토큰 예산 50,000(LLM_DAILY_TOKEN_BUDGET) + 사용량 Redis 기록 — 아래 "비용 통제" 메모
  - [x] 6) V6 coupons.description (VARCHAR(500) NULL, 공백만이면 CHECK로 거부): 생성 요청·응답(목록·상세)에 description.
        앱은 앞뒤 공백 제거, 공백만이면 null, 501자 이상 400. 길이 상한 = LLM 후보 설명 상한(테스트로 고정). 요청에 없으면 null(하위 호환)
  - [x] 7) 프론트: 관리자 폼에 설명 칸 + AI 문구 추천 패널(키워드·톤 → 2초 폴링 → 후보 3개 → 고르면 이름·설명 채움, 저장은 [이벤트 만들기]),
        사유별 안내·재시도, 상세 화면에 설명 — 아래 "프론트엔드 (6단계)"의 "AI 문구 추천" 메모. 브라우저 수동 확인은 아직
  - [x] 8) compose `llm` 프로필 + llm-service Dockerfile(멀티스테이지, JRE·비root·헬스체크), Kafka INTERNAL 리스너(kafka:29092) 추가,
        **Kafka 데이터가 볼륨에 안 남던 1단계부터의 버그 수정**(KAFKA_LOG_DIRS), 수동 확인 절차 docs/manual-check.md.
        실제 확인: 이미지 빌드 → 4개 컨테이너 healthy → llm-requests 직접 발행 → 컨테이너가 kafka:29092 로 받아 COMPLETED 응답,
        kafka 컨테이너를 다시 만들어도 토픽 유지
  - [x] 수동 확인 2026-11-07 (docs/manual-check.md A~G 모두 통과, fake 제공자): 로그인·발급·관리자 생성·AI 문구(캐시·한도)·
        장애 격리(llm-service 중단 → TIMEOUT, 재기동 시 마감 지난 요청 건너뜀)·예산 초과·모바일 폭
  - [ ] **실제 Claude API 호출은 아직 한 번도 확인 안 함** (H 미실행, 비용). 처음 anthropic으로 켤 때 manual-check H 절차로 1회 확인할 것 —
        SDK의 Bearer 키 전달, fallbacks 형식, 구조화된 출력 스키마가 실제 API에서 통하는지
  - [ ] 다음 기능: 자연어 검색 → MCP 도구 계층 → 챗봇(오케스트레이터) — 계획 2026-11-09 수립, 아직 시작 안 함.
        설계는 아래 "LLM 다음 기능 계획" 메모. 2026-11-10 결정: 순서 A → B → C, 벡터 검색 없이, API 키 없음(fake로 진행)
    - [ ] 0) 사전: manual-check H로 실제 Claude 호출 1회 확인 — API 키가 없어 **C2(tool use) 전까지 미룸**
    - [x] A1) 계약: LlmRequestType.EVENT_SEARCH, EventSearchInput(query ≤ 200), 결과 searchFilter = EventSearchFilter
          (keywords ≤ 5개·각 50자, statuses, start/end 범위 [from, to), availableOnly), EventSearchStatus(= CouponStatus 이름, CouponTest 검사),
          V7 ck_llm_requests_type에 EVENT_SEARCH — 아래 "LLM 메시지 계약" 메모. 수동 확인 2026-11-11(Kafka 직접 발행 → FAILED 응답, 문구 생성 회귀 OK)
    - [x] A2) llm-service: LlmClient.extractSearchFilter(input, 오늘 KST 날짜), fake 규칙(FakeEventSearch),
          Anthropic event-search.v1 프롬프트 + EventSearchOutput(날짜 문자열 양끝 포함) + 검증기, 캐시 키에 기준 날짜 — 아래 "자연어 검색 (llm-service)" 메모.
          수동 확인 2026-11-13(Kafka 직접 발행: 이번 주·내일·상태·남은 수량·빈 조건, 같은 질문 cached, Redis 사용량, AI 문구 추천 회귀 OK)
    - [x] A3) coupon-api: POST /api/coupons/search(공개, 202 PENDING 또는 200 FALLBACK) + GET /api/coupons/search/{id} 폴링,
          조건 → Criteria 조회(CouponSpecifications), 한도·예산·발행 실패·LLM 실패·마감 15초 → 키워드 검색 fallback — 아래 "자연어 검색 (coupon-api)" 메모.
          수동 확인 2026-11-13(bootRun 2개 + curl: AI 조건 COMPLETED(상태·예정·남은 수량·빈 조건), llm-service 중단 → 15초 후 FALLBACK/TIMEOUT,
          분당 한도 초과 → 즉시 FALLBACK/RATE_LIMITED, 목록·AI 문구 추천 회귀 OK).
          한도 확인은 결과를 기다리지 않고 POST만 연속으로 (fake 꺼진 상태에서 폴링까지 하면 1회 15초라 분 한도에 안 걸림)
    - [x] A4) 프론트: 이벤트 목록 검색창(?q=) → AI 조건 칩 / 키워드 검색 안내 + 결과 카드 — 아래 "프론트엔드"의 "자연어 검색" 메모.
          브라우저 수동 확인 2026-11-13 통과 (AI 조건 칩·URL ?q=·뒤로 가기 재사용·llm-service 중단 시 키워드 검색 안내·전체 보기·모바일 폭)
    - [x] B1) mcp-server 모듈 (8082, 공식 MCP Java SDK 2.1.0 코어, 서블릿 Streamable HTTP /mcp) — 조회 전용 도구
          search_events(PENDING이면 최대 20초 기다려 한 번에) / get_event. get_my_coupons는 B2(인증)와 함께 — 아래 "MCP 서버 (mcp-server)" 메모.
          실행 확인: bootRun → initialize 200 + 세션, 위조 Host 421, 루프백 외 주소 접속 불가.
          Claude Code 연결 확인 2026-11-14(claude mcp add --transport http): 검색 AI_FILTER·상세·발급 요청은 링크 안내·coupon-api 중단 시 도구 오류 설명 OK.
          B3에서 인증(B2)·get_my_coupons까지 다시 확인
    - [x] B2) 인증 + get_my_coupons(인자 없음) — 2026-11-17 결정: 사람은 access token 직접 입력(OAuth는 외부 공개 때), 서비스 토큰도 B2에서.
          사람 = Authorization: Bearer → coupon-api /api/users/me/coupons에 그대로 전달 / 서버(C2 챗봇) = X-Mcp-Service-Token + X-Mcp-User-Id
          → coupon-api 내부 API GET /internal/users/{id}/coupons(X-Internal-Token, ROLE_INTERNAL_SERVICE). 새 env MCP_SERVICE_TOKEN·INTERNAL_API_TOKEN
          — 아래 "MCP 서버" 메모의 "인증 (B2)". 테스트: McpToolsTest(get_my_coupons·연결 인증), InternalApiTest
    - [x] B3) 수동 확인 2026-11-21 (docs/manual-check.md K1~K9): Claude Code에 Bearer 헤더로 연결 → get_my_coupons(웹 내 쿠폰과 일치)·
          search_events(AI_FILTER, 회원별 검색 한도)·get_event·발급은 링크 안내·남의 쿠폰 요청 무시, 로그아웃 토큰·헤더 없음 → "로그인이 필요합니다",
          헤더 거절 curl(틀린 서비스 토큰·ID만 401, 잘못된 ID·Bearer 동시 400, 정상 200, 내부 API에 사용자 JWT 403)
    - [x] C1) 대화 저장 + 질문 API — 아래 "챗봇 대화 (7단계 C1, coupon-api)" 메모. V8 chatbot_sessions / chatbot_turns(턴 = 질문 + LLM 요청, 답은 llm_requests에만),
          POST /api/chatbot/sessions/{id}/turns(202 + 폴링), 방마다 대기 중 질문 1개(409), 한도 분 5·일 50(별도 카운터), 최근 답 받은 10턴을 history로.
          계약: LlmRequestType.CHATBOT(회원만), ChatbotInput / ChatbotTurn / ChatbotReply. (C1 당시 llm-service는 임시 고정 답변 → C2에서 실제 챗봇으로 교체)
          챗봇은 캐시하지 않음. 테스트: ChatbotApiTest(17), 계약·llm-service 챗봇 테스트. 실제 실행 확인은 아직
    - [x] C2) llm-service 챗봇 — 아래 "챗봇 (7단계 C2, llm-service)" 메모. ChatbotOrchestrator(tool use 루프: 도구 5회·질문당 토큰 20,000·마감 5초 전 중단,
          넘으면 도구를 끄고 마지막 답 1회), McpToolGateway(공식 MCP SDK 클라이언트, 질문마다 연결, X-Mcp-Service-Token + X-Mcp-User-Id),
          FakeChatbotModel(말 규칙 → 도구 1개 → 틀 답, 발급 요청은 검색 + 링크 안내) / AnthropicChatbotModel(Claude tool use, 응답 그대로 이어 붙임).
          **챗봇 전용 토픽 llm-chatbot-requests + 전용 스레드**(검색 도구가 만드는 EVENT_SEARCH를 막지 않게), 서버 호출 검색은
          coupon-api 내부 API /internal/users/{id}/coupons/search(회원별 한도). 새 env MCP_SERVER_URL. 테스트: ChatbotOrchestratorTest·FakeChatbotModelTest·
          McpToolGatewayTest(내장 Tomcat + SDK 서버)·AnthropicChatbotModelTest(WireMock)·CostControlFlowTest. 실제 실행 확인은 아직
    - [x] C3) 실제 연결 확인 2026-11-19 (docs/manual-check.md I1~I7, fake): bootRun 3개 + API로 내 쿠폰·검색(AI 경로)·상세·발급 거절·
          다른 회원 사칭 무시·회원별 검색 한도·mcp-server 중단 시 즉시 LLM_UNAVAILABLE(다른 API 정상). 답 0.8~2.3초.
          주의: PowerShell 5.1 스크립트는 BOM 있는 UTF-8로 저장해야 한글 리터럴이 안 깨진다 (확인 중 이벤트 이름이 깨져 검색 0건이 나왔음 — 앱 문제 아님)
    - [x] C4) 프론트 챗봇 창 — 아래 "프론트엔드"의 "챗봇 창" 메모. 헤더 [AI 챗봇](로그인 시) → 오른쪽 아래 창(모바일 전체 화면),
          대화방 조회를 마지막 턴이 PENDING일 때만 1초 폴링, 답 속 주소는 링크(같은 사이트면 화면 이동), 실패 사유·[다시 보내기], [새 대화].
          테스트 ChatbotPanel.test.tsx(9). 브라우저 수동 확인 2026-11-20 완료 (docs/manual-check.md J).
          확인 중 메모: fake 검색은 "커피쿠폰"(붙여 쓰기)·"진행"(한 단어)을 못 알아들어 0건 — fake 규칙 한계로 두기로 함(고치지 않음, 실제 모델은 이해)
  - [ ] D. WebSocket 이벤트 채팅방 (사람 ↔ 사람) — 목적: WebSocket 경험(연결·STOMP·연결 인증·scale-out). 계획 2026-11-18,
        B2·B3를 끝낸 뒤 시작. 설계는 아래 "WebSocket 채팅 계획" 메모. 챗봇(C)과는 별개 기능 (챗봇 답변 푸시는 SSE가 더 맞음)
    - [x] D1) web-chat-service 모듈(8083): Spring WebSocket + STOMP(SockJS 없음, 단순 브로커), 이벤트별 방 /topic/events/{id}, 보내기 /app/events/{id}/chat,
          메시지 500자(코드 포인트)·회원별 도배 제한(10초 5개, 미끄러지는 창, 탭 합산), 거절 사유 /user/queue/errors(연결 유지) /
          주소 위반·비로그인 보내기는 ERROR 프레임 + 연결 종료, Origin 허용 목록, 프레임 8KB·느린 클라이언트 제한, 하트비트 10초.
          D1 당시 인증은 인터페이스만(전원 비로그인) → D2에서 티켓 인증 — 아래 "채팅 서버 (D1)" 메모.
          테스트: WebChatRoomTest(실제 STOMP 클라이언트)·WebChatServiceTest·WebChatRateLimiterTest·WebChatRulesTest. bootRun 기동·헬스·Origin 403 확인
          (2026-11-21 이름 정리로 chat-service → web-chat-service, 컨트롤러/서비스 분리 — 공통 메모 "이름 규칙")
    - [x] D2) 연결 인증 — 아래 "채팅 연결 티켓 (D2)" 메모. coupon-api POST /api/web-chat/tickets(로그인, 패키지 com.couponrush.webchat)
          → 무작위 32바이트(base64url 43자) → Redis web-chat:ticket:{SHA-256} = {userId, nickname, issuedAt}, 30초, no-store 응답
          → web-chat-service가 STOMP CONNECT ticket 헤더로 GETDEL(1회용, 원자적) → 그 회원으로 연결. 티켓 없음 = 비로그인(읽기만),
          없는·만료·재사용·형식 오류·깨진 내용 = ERROR WEB_CHAT_UNAUTHORIZED + 연결 종료. 계약 모듈 libs/web-chat-contract(키 규칙·값 모양).
          인증 인터페이스는 구현이 하나라 없앰(WebChatTicketAuthenticator 클래스). 새 env 없음(REDIS_HOST/PORT 재사용).
          테스트: WebChatTicketsTest(키 고정 해시·형식·JSON), WebChatTicketApiTest(7), WebChatRoomTest(18: 실제 티켓으로 연결, 재사용·없는 티켓·형식·
          동시 8연결 중 1개만·깨진 내용), ArchitectureTest(webchat 규칙 2개). 두 서비스를 함께 띄운 실제 확인은 D4(화면)에서
    - [x] D6) 대화 저장 (2026-11-22, 순서 변경: D2 → **D6** → D3 → D4 — 화면(D4)에서 지난 대화까지 한 번에 만들려고) — 아래 "대화 저장 (D6)" 메모.
          web-chat-service **자기 데이터베이스 `web_chat`**(같은 Postgres 서버, 전용 계정 web_chat, 자기 Flyway V1 web_chat_messages) — coupon_rush 에는 접속 권한도 없음
          ("서비스마다 자기 DB" 첫 사례). 계정·DB는 초기화 스크립트 infra/postgres/20-web-chat-db.sh(기존 볼륨은 직접 1회 실행). 새 env WEB_CHAT_DB_PASSWORD(필수)·WEB_CHAT_DB_USER.
          방에 먼저 보내고 메모리 대기열 → 전용 스레드가 0.2초마다 묶음 INSERT(ON CONFLICT message_id 무시), 실패는 1s→2s→4s→8s 재시도 후 버림 — DB가 죽어도 대화는 계속.
          지난 대화 GET /api/web-chat/rooms/{eventId}/messages?beforeId=&size= (공개, 최근 50 기본·최대 100, id 커서, nextBeforeId).
          테스트: WebChatHistoryWriterTest(5: 묶음·넘침·재시도 간격·순서·포기·종료 저장), WebChatHistoryTest(5: 저장 값·페이지·중복·400·DB 장애 중 보내기 계속 → 복구 후 저장),
          WebChatRoomTest 그대로 통과. 초기화 스크립트는 임시 컨테이너로 확인(계정 생성·coupon_rush 접속 거절·재실행), 임시 DB에 bootRun → 일반 계정으로 Flyway 적용·조회 200/400
    - [x] D3) scale-out (2026-11-23) — 아래 "서버 간 전달 (D3)" 메모. Redis 채널 web-chat:messages 하나(모든 방), **보낸 서버도 Redis를 거쳐 받음**(모든 서버가 같은 순서),
          리스너는 단일 스레드(컨테이너 기본 실행기는 메시지마다 새 스레드 → 순서 뒤섞임), 저장은 받은 서버만 한 번, Redis 발행 실패 = WEB_CHAT_UNAVAILABLE(저장 안 함).
          LocalWebChatMessagePublisher → RedisWebChatMessagePublisher + WebChatRelayListener. 새 env 없음.
          테스트: WebChatScaleOutTest(4: 같은 컨테이너로 두 번째 앱 B를 띄워 A↔B 양방향·저장 1회(B 저장 수 0)·두 서버 같은 순서·깨진 전달 메시지 버림),
          RedisWebChatMessagePublisherTest(2), WebChatServiceTest(발행 실패 → 저장 안 함). 실제 bootRun 2대(8083·8084) 기동 → PUBSUB NUMSUB 2 확인.
          + 도배 제한을 서버 메모리 → **Redis**로(web-chat:rate:{userId} ZSET 미끄러지는 창, Lua 하나) — 서버가 여러 대여도·다른 서버로 다시 연결해도 회원 하나로 합산.
          테스트 WebChatRateLimiterTest(8, 실제 Redis: 서버 두 대 한 장부·동시 32스레드·키 TTL·Redis 끊김 → WEB_CHAT_UNAVAILABLE), WebChatScaleOutTest에 A·B 합산 거절 추가(5)
    - [x] D4) 프론트 채팅 패널 (2026-11-24, 브라우저 창 2개 수동 확인 docs/manual-check.md L1~L11 통과) — 아래 "프론트엔드"의 "이벤트 채팅" 메모.
          이벤트 상세 아래 WebChatPanel(@stomp/stompjs 7.5.0, 지수 백오프 재연결 1s→30s, 연결마다 새 티켓), 구독 먼저 → 지난 대화(useInfiniteQuery, beforeId) → messageId로 합침,
          다시 연결되면 지난 대화 다시 읽기, 비로그인은 읽기만 + 로그인 안내. Vite 프록시 /api/web-chat/rooms·/ws/web-chat(ws) → 8083을 /api보다 먼저(routes.test.ts가 순서 검사).
          테스트: api/webChat.test.ts(8, stompjs 대역), components/WebChatPanel.test.tsx(12), EventPages(연결 1회). 실제 확인: 5173 프록시 경유 Node 스크립트(티켓은 Redis 직접)로
          회원 2 + 비로그인 1 송수신·도배 거절·비로그인 보내기 ERROR·지난 대화 페이지·티켓 API 8080 라우팅 OK
    - [-] D5) **건너뜀 (2026-11-24 결정)** — 남은 수량 실시간 푸시(상세 3초 폴링 대체). 실무에서 흔한 방식은 폴링 + 짧은 캐시라서,
          8단계 배포 때 리버스 프록시(Nginx) 1초 캐시(proxy_cache_lock)로 대신한다 — 1만 명이 보고 있어도 coupon-api 조회는 이벤트당 초당 약 1번.
          최근 50개 Redis 보관은 D6(DB 조회)로 대신함 — 조회가 몰려 DB가 부담되면 그때 캐시로
- [x] 보안 업데이트 (2026-11-25): 프론트 npm audit 7건 → 0건 — axios 1.21.1, react-router-dom 7.18.7, vite 6.4.6, vitest 4.1.12 (아래 프론트 "테스트 메모")
- [ ] **⚠ 다음 작업(8단계) 시작 전에 Claude가 먼저 말할 것 — 2026-11-25 사용자 요청**: 아직 안 한 보안 검사 3가지
      ① 백엔드 Gradle 의존성 (OWASP Dependency-Check 등) ② Docker 이미지 postgres·redis·kafka (Trivy / docker scout) ③ Node.js 실행 프로그램 (이 PC v26.13.0, 공식 보안 공지와 비교).
      끝나면 이 항목을 [x]로 바꾸고 결과를 적는다
- [ ] 8. AWS + Kubernetes 배포 — 2026-11-24 결정: Spring Batch보다 먼저. **방식 미정**, 후보:
      EKS(클러스터만 시간당 $0.10 ≈ 월 $73, 노드·ALB·NAT 포함 24시간이면 약 $150~200 → 공부할 때만 켜고 지우기) /
      EC2 + k3s(같은 kubectl·매니페스트, 약 $40~70) / 로컬 Kubernetes(Docker Desktop, $0)로 매니페스트를 먼저 다듬기.
      데이터 저장소(2026-11-24 결정): Postgres·Redis·Kafka 모두 **클러스터 안**(StatefulSet + PersistentVolume) — 비용이 거의 안 늘고
      로컬 Kubernetes 매니페스트를 그대로 쓴다. 관리형은 10단계에서 Postgres만 RDS로(MSK는 비용 때문에 안 함). 정할 것: 도메인·HTTPS. 같은 도메인 + 리버스 프록시 구조 유지(CORS 없음),
      상세 조회 1초 캐시(D5 대신), /internal/** 은 밖으로 내보내지 않음. AWS 예산 알림을 먼저 걸 것
      **진행 순서(2026-11-24 결정): 먼저 로컬 Kubernetes(Docker Desktop)에서 끝까지 동작시킨다.**
      ⚠ **AWS에 올리기 전에 사용자에게 다시 물어본다** — EKS(필요할 때만 켜기) vs EC2 + k3s, 인증서(ACM vs cert-manager), 도메인.
      그때 참고: EKS 무료 등급은 없음(클러스터 $0.10/시간). 신규 계정 크레딧 최대 $200(가입 $100 + 서비스 사용 $100)으로 EKS·EC2·ELB·RDS·ElastiCache 사용 가능,
      MSK는 유료 플랜만. 무료 플랜 계정은 6개월 또는 크레딧 소진 시 닫힘(그 전에 유료 플랜 전환), 예전에 AWS 계정이 있었으면 대상 아님.
      필요할 때만 켜면 월 $20~30 → 크레딧으로 약 6개월(2026-11-24 확인, 올릴 때 요금·조건 다시 확인)
- [ ] 9. Spring Batch — 2026-11-22 결정: 후보 ①(종료된 이벤트 결과 집계), 별도 모듈 coupon-batch. 아래 "Spring Batch 계획 (E)"
- [ ] 10. Postgres → AWS RDS (마지막, 2026-11-24 결정) — 배포·배치가 끝난 뒤. 실무에서 가장 흔한 조합(앱은 Kubernetes, 운영 DB는 관리형)을
      직접 옮겨 보며 차이(백업·접속 설정·비용)를 비교. web_chat 데이터베이스·계정(D6)과 coupon_batch 계정·batch 스키마(9단계)도 함께 옮겨야 한다

## 결정 사항 / 메모

### 공통 / 인프라
- Spring Boot 3.5.16 고정: Spring Initializr가 4.x만 제공해서 Wrapper만 받고 build.gradle에서 수동 지정.
  Gradle Wrapper는 Boot 3.5 호환을 위해 8.14.3.
- Gradle 멀티모듈 (7단계): backend/가 루트(settings.gradle, gradlew, 공통 설정은 루트 build.gradle의 subprojects).
  모듈 의존성은 각 모듈 build.gradle에. 실행·테스트 명령은 backend/에서 `:coupon-api:bootRun`처럼 모듈을 지정한다
  (`--tests` 필터는 모든 모듈에 걸리므로 모듈 지정 필수). bootRun·test의 작업 폴더는 모듈 폴더 → .env는 ../../infra/.env.
  모듈 간 공유는 계약 모듈만(libs:llm-contract, libs:web-chat-contract) — 엔티티·보안·에러 코드는 공유하지 않는다.
- 패키지 (coupon-api): com.couponrush.{auth, user, coupon, llm, search, chatbot, global(config, error)}
- **패키지 구조 = 기능 우선(package-by-feature), 기능 안에서 계층(Controller → Service → Repository)**. 모든 모듈 같은 규칙 —
  입구(HTTP 컨트롤러 / STOMP 컨트롤러 / MCP 도구 / Kafka 리스너)는 프로토콜만, 업무 흐름은 Service. mapper 없음(JPA Repository가 그 자리).
  2026-11-21 정리: web-chat-service에 WebChatService, mcp-server에 event/EventQueryService를 분리 (llm-service는 이미 Consumer → Processor/Orchestrator).
- **이름 규칙 (2026-11-21)**: AI 챗봇 = **chatbot**(패키지 chatbot, 클래스 Chatbot*, API /api/chatbot/**, 테이블 chatbot_*, 코드 CHATBOT_*),
  사람끼리 WebSocket 채팅 = **webChat**(모듈 web-chat-service, 패키지 webchat, 클래스 WebChat*, 코드 WEB_CHAT_*, 설정 app.web-chat, 연결 /ws/web-chat).
  그냥 "chat"은 쓰지 않는다 (둘이 섞여서 D2 티켓 API가 챗봇 경로 /api/chat/** 와 겹칠 뻔함). 이전 이름 chat_* 테이블은 V9로, 계약은 SCHEMA_VERSION 2로 바꿈.
- 이미지: pgvector/pgvector:pg17, redis:7.4-alpine, apache/kafka:3.9.1 (KRaft 단일 노드)
- Kafka 리스너 2개: PLAINTEXT://localhost:9092(호스트에서 실행하는 앱·도구용, 기존) + INTERNAL://kafka:29092(compose 네트워크 안 컨테이너용).
  컨테이너가 localhost:9092로 붙으면 자기 자신을 가리킨다. 호스트에는 29092를 열지 않는다.
- Kafka 데이터 위치는 KAFKA_LOG_DIRS=/var/lib/kafka/data (볼륨). 이미지 기본값 /tmp/kraft-combined-logs라서 7단계 전까지는 볼륨이 쓰이지 않아
  컨테이너를 다시 만들면 토픽·오프셋이 사라졌다 (재시작은 괜찮았음). 토픽은 각 앱이 기동 시 NewTopic으로 다시 만든다.
- llm-service 컨테이너(`docker compose --profile llm up -d --build`): 빌드 컨텍스트 backend/, Dockerfile은 llm-service/Dockerfile,
  .dockerignore가 build·.gradle·coupon-api/src 제외. 환경변수는 LLM_*·Kafka·Redis만 넘기고 .env를 통째로 주지 않는다(비밀값 분리).
  포트를 열지 않는다. 호스트 bootRun과 동시에 켜지 않는다(같은 컨슈머 그룹).
  **계약(llm-contract)이나 llm-service 코드가 바뀌면 `--build`로 다시 빌드한다** — 2026-11-14 11-09 빌드 이미지가 EVENT_SEARCH를 몰라
  역직렬화 실패 → DLT → 결과 없음 → 검색이 매번 FALLBACK/TIMEOUT이 됐다 (로그: "not one of the values accepted for Enum class").
- 접속 정보/비밀값은 infra/.env 하나로 관리. docker-compose와 Spring(application.yml의
  spring.config.import: optional:file:../../infra/.env[.properties], 작업 폴더 backend/coupon-api 기준)이 같은 파일을 읽는다. .env는 기동 시에만 읽음.
- 현재 시각은 Clock Bean(ClockConfig)으로 주입 → 테스트에서 시간 고정.
- 에러 응답은 {"code","message"} (global/error: ErrorCode, BusinessException, GlobalExceptionHandler).
  @Valid 실패·JSON 형식 오류·경로 타입 오류는 400 INVALID_INPUT.

### 테스트
- Testcontainers로 compose와 동일한 이미지를 띄워 검증한다 (Docker 실행 필요, 실행 중인 앱/로컬 DB와 무관).
- 통합 테스트는 @IntegrationTest 메타 애너테이션 사용 (더미 비밀값 고정 + 컨텍스트 캐시 공유).
- 테스트는 각자 만든 데이터를 @AfterEach에서 정리한다 (issue_requests → coupon_issues → coupons → users 순서, FK 때문.
  Redis는 event:*, issue-request:*, refresh:*). 쿠폰 테스트는 ConcurrencyTestSupport.cleanUp 사용 —
  컨슈머가 남은 메시지를 처리할 때까지(PENDING 키가 없어질 때까지, 최대 15초) 기다린 뒤 지운다.
- 비동기 결과는 Awaitility로 기다린다 (Thread.sleep 금지, 단 "두 번째 메시지가 처리될 시간" 같은 부정 검증은 예외).
- 쿠폰 테스트에서 쿠폰을 만들 때는 ConcurrencyTestSupport.saveAndLoad/saveOpenCoupon으로 Redis에도 적재한다
  (IssueStrategy 파라미터 테스트에 REDIS가 포함되므로).

### DB / Flyway
- 스키마는 Flyway로만 변경한다 (backend/coupon-api/src/main/resources/db/migration/V{n}__설명.sql).
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
- Security: 기본은 전부 인증 필요. 공개 경로는 /actuator/health, POST /api/auth/refresh, GET /api/coupons, GET /api/coupons/*,
  POST /api/coupons/search, GET /api/coupons/search/* (A3).
  /api/admin/**는 ROLE_ADMIN. stateless, CSRF/폼로그인 off (OAuth2 state 보관에만 세션 잠깐 사용).
- 챗봇 /api/chatbot/** (C1)은 로그인 필요 — 기본 규칙(anyRequest().authenticated())이라 SecurityConfig에 따로 없다.
- 서비스 간 내부 API /internal/** (B2): X-Internal-Token = INTERNAL_API_TOKEN이면 ROLE_INTERNAL_SERVICE (InternalServiceAuthenticationFilter).
  사용자 JWT(ADMIN 포함)로는 403 — 경로의 userId를 그대로 믿는 API라서. 토큰은 /internal/**에서만 동작(사용자 API에 못 씀), 미설정이면 전부 401(닫힘),
  비교는 MessageDigest.isEqual. Vite 프록시는 /api만 넘기므로 브라우저에서 닿지 않고, 배포 시 리버스 프록시도 /internal/**을 내보내지 않는다.
- JWT에는 userId(sub), token_type(access|refresh), jti만 담고 access에만 role 추가. 개인정보는 넣지 않는다.
  token_type으로 refresh를 Bearer로 쓰는 것(또는 반대)을 막는다.
- 토큰 전달 (6단계에서 변경): **URL에는 토큰을 싣지 않는다.** 로그인 성공 시 refresh만 HttpOnly 쿠키로 심고
  프론트 콜백(/oauth/callback)으로 리다이렉트 → 프론트가 POST /api/auth/refresh로 access를 받아 **메모리에만** 둔다.
  (예전 방식 #accessToken= fragment는 방문 기록·주소창·페이지의 모든 스크립트에 노출돼서 제거. 새로고침 복원도 같은 refresh 호출)
  로그인 실패는 #error=login_failed (에러 코드만).
  refresh 쿠키: refresh_token, HttpOnly, Path=/api/auth, SameSite=Strict, Secure는 AUTH_COOKIE_SECURE.
  배포 시 프론트/백엔드가 다른 사이트면 SameSite=None + Secure로 바꿔야 한다.
- Redis 키: refresh:{userId} → SHA-256(refresh token), TTL 14d (사용자당 1개, 재로그인 시 이전 기기 무효)
  blacklist:{jti} → 로그아웃된 access token, TTL = 남은 유효시간. JwtAuthenticationFilter가 매 요청 확인.
- refresh 시 role은 DB에서 다시 읽는다. refresh token rotation은 아직 안 함 (추후 선택).
- 인증 실패: 401은 JsonAuthenticationEntryPoint (UNAUTHORIZED / TOKEN_EXPIRED / TOKEN_INVALID / TOKEN_REVOKED /
  REFRESH_TOKEN_*), 403은 JsonAccessDeniedHandler (FORBIDDEN). 필터 자체는 응답하지 않고 사유만 request attribute에 남긴다.
- JwtAuthenticationFilter는 @Component로 등록하지 않는다 (서블릿 필터 자동 등록으로 두 번 실행되는 것 방지).

### 쿠폰
- API: GET /api/coupons(목록, 공개), GET /api/coupons/{id}(공개), POST /api/coupons/{id}/issue, GET /api/users/me/coupons,
  POST /api/admin/coupons(ROLE_ADMIN). 발급자는 토큰에서만 꺼낸다 (바디로 userId 받지 않음).
  화면 용어 "이벤트" = 백엔드 coupons 한 행 (API 경로는 /api/coupons 유지).
- description(V6, 선택, 최대 500자): 관리자가 직접 쓰거나 LLM 문구 후보를 골라 고친 값 — 서버는 출처를 구분하지 않고, LLM 결과를 자동 저장하지 않는다.
  Coupon.DESCRIPTION_MAX == EventCopyCandidate.DESCRIPTION_MAX (CouponTest가 검사 — 후보를 그대로 넣어도 넘치지 않게).
- 조회 응답(CouponResponse)에 status(UPCOMING/OPEN/ENDED, 서버 Clock 기준)와 serverTime 포함.
  remainingQuantity는 Redis 재고 우선(판정 기준, 비동기 모드에서 DB 카운터는 컨슈머만큼 늦음), 없으면 DB.
  목록은 오픈 시각 최신순 50개, Redis 재고는 MGET 한 번(RedisCouponStock.getStocks).
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
코드: coupon/stock/ (RedisCouponStock = Redis 재고 저장소, RedisIssueResult), coupon/issue/ (RedisCouponIssuer, RedisIssueRejections), 스크립트: resources/redis/*.lua
- 패키지 (2026-11-16 정리): 재고는 발급 외에도 목록 조회(CouponService)·대조 점검·async가 함께 쓰므로 coupon/stock/에 둔다
  (예전 coupon/issue/redis/에 있을 때는 발급과 무관한 대조 점검·목록 조회가 **발급 구현의 내부 패키지에 의존**했다 — 형제 기능 사이의 잘못된 의존).
  패키지는 기술(redis)이 아니라 역할로 나눈다. 규칙은 ArchitectureTest(ArchUnit)가 검사한다.
  남은 구조 한계: 상위 coupon 패키지에 도메인(Coupon·CouponIssue·Repository)과 애플리케이션(Service·Controller)이 섞여 있어
  coupon ↔ coupon.issue/stock/search/dto 순환 의존이 있다 (하위는 도메인 때문에 위를, 서비스는 하위 기능 때문에 아래를 본다).
  없애려면 도메인을 coupon/domain으로 내리면 된다 — 파일 이동이 커서 보류(2026-11-17).

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
| `event:{couponId}:restored` | set | 보상(복구)한 requestId — 이중 복구 방지 + 취소된 요청 판별 (6장). 재적재해도 유지 |
| `issue-request:{requestId}` | hash | 비동기 PENDING (couponId, userId, requestedAt), TTL 1일 (5장) |

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

### Kafka 비동기 발급 (5단계, 기본 모드) — 상세: docs/concurrency-notes.md 5~7장
코드: coupon/issue/async/ (AsyncCouponIssueRequester, CouponIssueEventConsumer, CouponIssueEventProcessor, CouponIssueKafkaConfig,
CouponIssueConsumerProperties, CouponIssueConsumerMetrics), coupon/request/ (IssueRequest, PendingIssueRequestStore, 조회 API),
coupon/reconcile/ (대조 점검), V4(issue_requests)

**모드 전환**: app.coupon.issue.mode (COUPON_ISSUE_MODE) = async(기본, 202) | sync(201, strategy 사용, 4단계까지의 방식).
CouponIssueService.request()가 분기하고, 결과는 IssueResult(Issued / Accepted) sealed 타입 → 컨트롤러가 201 / 202로 매핑.

**토픽 구조**

| 토픽 | 파티션 | 키 | 값 | 컨슈머 그룹 |
|---|---|---|---|---|
| `coupon-issue-requests` | app.kafka.topic.partitions (기본 3) | eventId(쿠폰 ID) | JSON `CouponIssueRequestedEvent(requestId, eventId, userId, requestedAt)` | `coupon-issue-consumer` (리스너 id `couponIssueListener`, concurrency 기본 3) |
| `coupon-issue-requests.DLT` | 메인과 같은 수 (원본 파티션 번호로 보냄) | 원본 그대로 | 원본 + 예외 헤더(`kafka_dlt-exception-*`) | `coupon-issue-dlt-consumer` (전용 컨테이너, 2초 간격 무한 재시도) |

- replicas = app.kafka.replicas (로컬 1, 운영 3 + min.insync.replicas=2). 파티션은 늘리기만 가능.
- Producer: acks=all, enable.idempotence=true, max.in.flight ≤ 5, delivery.timeout 10s. 값은 ObjectMapper + StringSerializer(JSON 문자열).

**파티션 키 = eventId(쿠폰 ID)**
- 같은 쿠폰은 항상 같은 파티션 → 한 컨슈머 스레드가 순서대로 처리 → `coupons` 행 락 경합이 없다 (3~4단계의 병목 제거).
- 대가: 이벤트 하나는 한 파티션·한 스레드에 몰림 → 파티션·스레드를 늘려도 **이벤트 하나는 빨라지지 않는다** (측정으로 확인).
  여러 이벤트가 동시에 열리면 늘린 만큼 병렬. 파티션은 "동시에 열리는 이벤트 수"보다 넉넉히 (해시 충돌로 몰림).
- userId 키는 부하는 고르지만 같은 쿠폰의 쓰기가 흩어져 행 락 경합이 돌아오고, 쿠폰별 순서도 사라져서 채택하지 않음.

**202 흐름 (요청 경로에 DB 접근 없음)**
1. Lua 판정 (4단계와 동일). 실패면 409 / 400 / 503을 바로 응답하고 Kafka에는 보내지 않는다.
2. PENDING 기록 `issue-request:{requestId}` (TTL 1일, **발행 전에** — 컨슈머가 먼저 끝나도 순서가 뒤집히지 않게)
3. Kafka 발행 후 **브로커 ack(acks=all)를 최대 5초 대기**
4. 202 + `Location: /api/issue-requests/{requestId}`. 202 = "판정은 끝, DB 저장은 처리 중"(201은 리소스가 생겼다는 뜻이라 부적절)
5. 발행 실패 / 타임아웃 → 보상(restoreId = requestId) + PENDING 삭제 + 503 COUPON_ISSUE_UNAVAILABLE
- 상태 조회 GET /api/issue-requests/{id}: DB(COMPLETED/FAILED) → Redis(PENDING) → 404. 본인 것만 (남의 requestId는 존재 여부도 숨김).
- issue_requests에는 최종 결과만 저장 (PENDING은 DB에 안 씀). user_id FK 없음 (회원 FK 위반 실패도 FAILED로 남기려고).

**컨슈머: 멱등 / 재시도 / DLT / 커밋** (ack-mode=record, enable-auto-commit=false)
- 처리 순서: ① 멱등 1차 — issue_requests에 requestId가 있으면 건너뜀 → ② 취소 확인 — isRestored(couponId, requestId)면 발급하지 않고
  FAILED(PUBLISH_CANCELLED), 보상 안 함 → ③ 한 트랜잭션: coupon_issues INSERT → issued_quantity +1(기간 검사는 event.requestedAt 기준)
  → issue_requests COMPLETED
- 멱등 2차: 동시 처리로 ①을 함께 통과하면 coupon_issues 유니크 / issue_requests PK 위반 → 같은 requestId 결과가 있으면
  "중복"으로 정상 종료 (FAILED·보상 금지). 없으면 다른 요청의 발급 → FAILED(COUPON_ALREADY_ISSUED)
- 영구 실패(FK, DB 중복, 재고 불일치): **FAILED 먼저 기록 → 그다음 Redis 보상** (반대 순서면 재처리 시 초과 발급 위험)
- 일시 실패: 지수 백오프(app.kafka.consumer.retry: 3회, 0.5s ×2, 상한 5s). 재시도 중엔 같은 파티션도 대기(쿠폰별 순서 유지).
  소진 → DLT 발행. 역직렬화 실패(InvalidCouponIssueMessageException)는 재시도 없이 바로 DLT.
- DLT 컨슈머: 결과가 이미 있으면 건너뜀, 없으면 FAILED(RETRY_EXHAUSTED) → 보상 → PENDING 삭제. DB가 죽어 있으면 복구될 때까지 재시도.
- 오프셋: 리스너가 정상 반환한 뒤에만 커밋 = DB 커밋 성공 후. 예외면 커밋 없이 되감아 재시도, DLT 발행에 성공해야 오프셋을 넘김. 처리 전 커밋 금지.
- 메인 CommonErrorHandler Bean은 하나여야 Boot가 기본 컨테이너에 자동 적용한다 (DLT용 핸들러는 Bean으로 만들지 않음).
- 지표 CouponIssueConsumerMetrics: failedDeliveries(실패한 시도, 최초 포함: 재시도 3회면 4), dltPublished, duplicatesSkipped.
  Spring Kafka RetryListener.failedDelivery는 "실패할 때마다" 호출된다 (재시도 횟수 아님).
- FAILED 사유: DATA_INTEGRITY_VIOLATION / COUPON_ALREADY_ISSUED / STOCK_MISMATCH / RETRY_EXHAUSTED / PUBLISH_CANCELLED

**보상 로직 (멱등)**
- RedisCouponStock.restore(couponId, userId, removeUser, restoreId). Lua가 `event:{id}:restored`에 restoreId를 먼저 SADD하고, 처음일 때만 INCR + SREM.
  비동기는 restoreId = requestId → 발행 실패 / 컨슈머 실패 / DLT 어느 경로든 같은 요청은 **한 번만** 복구. 동기 경로는 "sync-" + UUID.
- removeUser=false는 DB에 이미 이력이 있는 경우(유니크 위반) — 재고만 되돌리고 Set에는 남김.
- restored는 재적재(load)해도 지우지 않는다 (Kafka에 남은 취소 요청 메시지 대비).
- 보상 실패는 "덜 발급"되는 방향(안전) → 로그 + 대조 점검으로 감지.

**대조 점검**: CouponStockReconciler — 불변식(남은 재고 + DB 발급 = 총 수량, Set = DB 사용자, 카운터 = 행) 위반만 WARN 로그, 수정하지 않음.
GET /api/admin/coupons/{id}/reconciliation, 스케줄러(app.coupon.reconcile.*, 10분, 종료 후 1분~7일 이벤트). 테스트에서는 꺼 둔다.

**남은 한계** (docs 6-4, 초과 발급은 어떤 경우에도 DB 제약이 최종 차단)

| 상황 | 결과 | 지금 |
|---|---|---|
| Lua 성공 직후, 발행 전에 서버 종료 | 유령 발급 (재고 -1, Set에 있음, DB·Kafka 없음) | 감지만 (대조 점검 "Redis에만 있는 사용자"). 근본 해결은 Lua 안 XADD / PENDING 워커 |
| 발행 타임아웃인데 실제로는 기록 + 컨슈머가 보상보다 먼저 처리 | 503인데 쿠폰 있음, Redis 재고 1 과다 | 감지만 ("DB에만 있는 사용자") |
| 보상 Lua 실패 | 덜 발급 | 로그 + 대조 점검 |
| 이벤트 하나의 DB 반영 속도 | 파티션당 약 50건/s (건당 처리) | 배치 컨슈머로 개선 예정 |
| 스케줄러를 여러 서버에서 실행 | 중복 실행(읽기 전용) | 운영 시 ShedLock |

**측정 결과** (AsyncIssueBenchmarkTest, 32스레드, Testcontainers, 로컬 Windows + Docker Desktop, 워밍업 1회 후 1회 측정, 정합성 매회 확인)

| 시나리오 | 방식 | 응답 p50 / p99 | 모든 응답 반환 | DB 반영 완료 |
|---|---|---|---:|---:|
| 재고 100 / 1,000명 | 동기 Redis | 5 / 195 ms | 527 ms | 527 ms |
| | Kafka 비동기 | 4 / 38 ms | 209 ms | 1,961 ms |
| 재고 100 / 10,000명 | 동기 Redis | 3 / 25 ms | 1,494 ms | 1,494 ms |
| | Kafka 비동기 | 4 / 18 ms | 1,494 ms | 2,292 ms |
| 재고 1,000 / 1,000명 | 동기 Redis | 72 / 178 ms | 2,508 ms | 2,508 ms |
| | Kafka 비동기 | **19 / 27 ms** | **620 ms** | 12,023 ms |

| 파티션 / 스레드 | 이벤트 1개 (1,000건) | 이벤트 6개 동시 (1,200건) |
|---|---:|---:|
| 3 / 1 | 20.0 s | 26.3 s |
| 3 / 3 | 14.6 s | 14.3 s |
| 6 / 6 | 16.2 s | **7.3 s** |

- 비동기는 사용자 응답(특히 p95 이상 꼬리)을 크게 줄이지만, DB 반영 완료는 건당 처리 때문에 동기보다 느리다.
- 파티션·스레드는 이벤트가 여러 개일 때만 효과가 있다. 완료 시간은 메시지가 가장 많이 몰린 파티션이 결정한다.

**테스트 메모**: DB 장애는 `ALTER TABLE issue_requests RENAME TO issue_requests_broken`으로 흉내 낸다
(CouponIssueConsumerReliabilityTest, @AfterEach에서 항상 복구). 커밋 오프셋은 AdminClient.listConsumerGroupOffsets로 확인.

### 프론트엔드 (6단계)
frontend/ — Vite 6 + React 19 + TypeScript, react-router-dom 7, @tanstack/react-query 5, axios. 버전은 package.json에 고정.

**코드 구조** (src/)

| 폴더 | 내용 |
|---|---|
| api/ | axios 호출·응답 타입(coupons, admin, auth, llm, search, chatbot, webChat(STOMP 연결 포함)), client.ts(인터셉터), tokenStore.ts, queryKeys.ts, errors.ts(에러 코드 → 메시지) |
| hooks/ | useEvents, useIssueCoupon(발급 상태 머신), useMyCoupons, useServerClock, useAdminEvents, useEventCopy(문구 생성 상태 머신), useEventSearch(자연어 검색), useChatbot(챗봇), useWebChat(이벤트 채팅) |
| components/ | Header, QueryState(로딩·에러·빈 상태), Spinner, StatusBadge, IssuePanel, ErrorBoundary, EventCopyPanel(AI 문구 추천), ChatbotPanel(챗봇 창), WebChatPanel(이벤트 채팅) |
| pages/ | EventList, EventDetail, MyCoupons, AdminEventCreate, Login, OAuthCallback, NotFound |
| auth/ | AuthContext(세션 상태), ProtectedRoute(로그인 필요), AdminRoute(ADMIN 필요) |
| utils/ | format(날짜·카운트다운), eventForm(관리자 폼 검증·변환) |

- **서버 데이터는 TanStack Query로만** (useState에 서버 응답을 복사하지 않는다). 키는 api/queryKeys.ts에서만 만든다.
  로딩·에러·빈 상태는 components/QueryState로 통일 (에러 문구 + [다시 시도] = refetch).
- **에러 메시지는 api/errors.ts에서만** — 백엔드 ErrorCode / FAILED 사유와 1:1. isFinalRejection(소진·이미 발급·기간 아님·없음)은 재시도 버튼 없음.

#### 화면 흐름

| 경로 (src/routes.ts) | 화면 | 접근 | API |
|---|---|---|---|
| `/` (`?q=` 검색) | 이벤트 목록 (상태 뱃지 오픈 전/진행 중/종료, 남은 수량) + 자연어 검색창 | 공개 | GET /api/coupons, POST /api/coupons/search, GET /api/coupons/search/{id} |
| `/events/:id` | 상세: 기간·남은 수량·카운트다운·[발급 받기] + 이벤트 채팅 | 공개 (발급·채팅 보내기만 로그인) | GET /api/coupons/{id} (3초마다), POST .../issue, GET /api/issue-requests/{id}, POST /api/web-chat/tickets, ws /ws/web-chat, GET /api/web-chat/rooms/{id}/messages |
| `/me/coupons` | 내 쿠폰 | 로그인 | GET /api/users/me/coupons |
| `/admin/events/new` | 이벤트 생성 폼(설명 포함) + AI 문구 추천 + Redis 재고 적재 확인 | ADMIN | POST /api/admin/coupons, GET .../reconciliation, POST /api/admin/llm/event-copy, GET /api/admin/llm/requests/{id} |
| `/login` | 카카오·네이버·구글 버튼 | 공개 | (페이지 이동) /oauth2/authorization/{provider} |
| `/oauth/callback` | 로그인 완료 처리 후 원래 화면으로 | 공개 | POST /api/auth/refresh |
| 그 외 | NotFoundPage (없는 이벤트·/events/abc 포함) | 공개 | — |

```
목록 → 상세 ─[발급 받기]─ 비로그인 → /login (state.from 기억) → 제공자 → /oauth/callback → 상세로 복귀
                        └ 로그인 → 202 → 폴링 → 완료 → [내 쿠폰 보기]
```
- 헤더: 이벤트 / 내 쿠폰(로그인 시) / 이벤트 만들기(ADMIN) 메뉴, 닉네임 + 로그아웃 또는 로그인 링크.
- ProtectedRoute: 세션 복원 중(loading)에는 "확인 중"을 보여 주고 기다린다 (새로고침 때 로그인 화면으로 깜빡 튕기지 않게).
  AdminRoute는 화면 숨김용일 뿐, 실제 권한 검사는 백엔드(/api/admin/** = ROLE_ADMIN).
- 에러: API 에러는 Query error 상태 → QueryState / 화면별 안내. 렌더링 예외는 ErrorBoundary
  (App 안: resetKey=pathname으로 이동하면 복구, main.tsx 바깥: 최후 안전망). 없는 이벤트(404)는 재시도하지 않고 NotFoundPage.
- 관리자 폼(utils/eventForm): 검증은 백엔드 CouponCreateRequest와 같게 (이름 1~100자, 수량 정수 1~1,000,000, 시작 < 종료)
  + 종료가 이미 지났으면 막음. 첫 제출 전에는 에러를 띄우지 않고, 제출 후에는 입력마다 재검증. datetime-local(PC 시간대) → toISOString(UTC).
  서버 400 INVALID_INPUT은 서버 message(필드별 사유)를 그대로 표시(errors.formErrorMessageOf). 생성 후 대조 API로 Redis 적재 확인(없으면 stock/sync 안내).
- 반응형: 600px 이하에서 gutter 16px, 헤더 줄바꿈(로고 첫 줄), 상세의 발급 버튼은 화면 하단 sticky(safe-area 포함),
  입력 글자 16px(iOS 확대 방지), 긴 이름 overflow-wrap: anywhere, 카운트다운 tabular-nums.

#### 인증 토큰 처리 방식과 이유

| 토큰 | 어디에 | 누가 다루나 | 이유 |
|---|---|---|---|
| access (30분) | **JS 메모리 변수**(api/tokenStore.ts)에만 | axios 인터셉터가 `Authorization: Bearer` 첨부 | localStorage·sessionStorage는 XSS 스크립트가 읽을 수 있다. 메모리는 새로고침하면 사라지지만 refresh로 다시 받으면 된다 |
| refresh (14일) | **HttpOnly 쿠키** `refresh_token` (Path=/api/auth, SameSite=Strict) | 브라우저만 (JS가 읽을 수 없음) | 탈취되면 오래 쓰이는 토큰이라 스크립트 접근 자체를 막는다. Path로 /api/auth 요청에만 실리고, SameSite=Strict로 다른 사이트발 요청(CSRF)에는 안 실린다 |

- **URL에 토큰을 싣지 않는다** (6단계에서 변경). 예전 `#accessToken=` fragment는 방문 기록·주소창·페이지의 모든 스크립트에 노출됐다.
  지금은 백엔드가 로그인 성공 시 refresh 쿠키만 심고 `/oauth/callback`으로 보낸다 → 프론트가 POST /api/auth/refresh로 access를 받는다.
  로그인 실패는 `#error=login_failed` (에러 코드만).
- **로그인 흐름**: [카카오로 로그인] → (location.assign) /oauth2/authorization/kakao → 제공자 → 8080 /login/oauth2/code/kakao
  → 백엔드가 회원 저장 + refresh 쿠키 → 5173/oauth/callback → refresh → access(메모리) → GET /api/users/me → 원래 화면.
  돌아갈 경로는 sessionStorage(coupon-rush:return-to)에 저장 (토큰이 아니라 경로만).
- **세션 복원**: 새로고침하면 메모리 access가 사라지므로 AuthProvider가 앱 시작 시 POST /api/auth/refresh 1회 → 성공하면 GET /api/users/me(['me']).
  상태: loading(복원 중) / authenticated / anonymous. refresh는 됐는데 /me가 실패해도 anonymous.
- **axios 인터셉터**(api/client.ts): 401이면 refresh 후 원래 요청을 **한 번만** 재시도(_retried).
  refresh는 **single-flight** — 동시 401 여러 개, StrictMode 이중 effect, 콜백 페이지와 앱 시작 복원이 겹쳐도 refresh 요청은 1번.
  /api/auth/* 자체의 401은 재발급하지 않음(무한 루프 방지). refresh 실패 → 토큰 삭제 + tokenStore.notifySessionExpired()
  → AuthContext가 anonymous로 → ProtectedRoute가 /login으로.
- 로그아웃: POST /api/auth/logout(서버가 refresh 삭제·쿠키 만료·access 블랙리스트) → 실패해도 메모리 토큰과 Query 캐시는 지운다.
- **같은 출처 + 프록시**: 개발 서버 5173이 /api, /oauth2, /login/oauth2를 8080으로 프록시 → 브라우저에겐 전부 localhost:5173
  → CORS 설정 불필요, SameSite=Strict 쿠키도 그대로 실린다. axios는 baseURL 없이 상대 경로. 백엔드에 CORS 설정을 추가하지 않는다
  (배포 때도 같은 도메인 + 리버스 프록시로 같은 구조 유지. 다른 사이트로 나누면 SameSite=None + Secure + CORS가 필요해진다).
- **프록시 규칙은 경로 앞부분 일치라 화면 경로와 겹치면 안 된다.** '/login' 전체를 넘겼더니 로그인 화면을 새로고침할 때 백엔드의 401 JSON이 보였다
  → 실제로 쓰는 /login/oauth2(OAuth 콜백)만 넘긴다. 화면 경로는 src/routes.ts(ROUTES)에 모으고 src/routes.test.ts가 겹침을 검사한다.
- /oauth2, /login/oauth2 프록시는 **changeOrigin: true** — Spring이 Host로 redirect_uri를 만들기 때문에 8080이 되어야 콘솔 등록 주소와 일치
  (false면 redirect_uri가 5173이 되어 "등록되지 않은 redirect_uri" 오류). 쿠키는 포트를 구분하지 않아서(localhost) 5173의 refresh 요청에도 실린다.

#### AI 문구 추천 (7단계, components/EventCopyPanel + hooks/useEventCopy)
```
[문구 생성] (키워드 1~200자, 톤 4종, 폼의 총 수량 + 올바를 때만 기간) ─ POST /api/admin/llm/event-copy
   ├─ 429 LLM_RATE_LIMITED / LLM_DAILY_LIMIT_EXCEEDED / LLM_BUDGET_EXCEEDED, 503 → rejected (일·예산 소진은 [다시 시도] 없음)
   └─ 202 → GET /api/admin/llm/requests/{id} 2초 폴링 (화면 타임아웃 75초 = 서버 마감 60초 + 여유)
         ├─ COMPLETED → 후보 3개 [이 문구 쓰기] → 폼의 이름·설명을 채움 (cached면 "같은 입력의 문구" 안내) + [다시 생성](regenerate)
         └─ FAILED → llmFailureMessage(사유), retryable 일 때만 [다시 시도]
```
- **결과는 제안일 뿐 — 자동 저장 없음.** 고르면 폼 칸만 채우고, 관리자가 고친 뒤 [이벤트 만들기]를 눌러야 POST /api/admin/coupons(description 포함).
- useEventCopy는 useIssueCoupon과 같은 구조(상태는 mutation + query에서 계산, inFlight ref로 연타 방지, 타임아웃은 "끝났는지"만 의존).
  EVENT_COPY_POLLING(2s/75s)은 화면 테스트에서 짧게 바꾸고, useEventCopy.test.tsx는 가짜 시계로 실제 값 검증.
- 메시지는 api/errors.ts의 LLM_* 코드와 llmFailureMessage(LLM_UNAVAILABLE·BUDGET_EXCEEDED·INVALID_OUTPUT·CONTENT_REFUSED·TIMEOUT·PUBLISH_FAILED).
- 설명: 폼 textarea(maxLength 500, 검증 500자), 비우면 null로 보냄. 상세·생성 완료 화면에 표시(white-space: pre-line).

#### 자연어 검색 (7단계 A4, pages/EventListPage + hooks/useEventSearch + api/search)
```
검색창 [검색] → URL ?q=질문 (공백 정리, 비우면 q 제거 = 전체 목록)
  useQuery ['event-search','query',q]: POST /api/coupons/search (재시도 없음, 5분 재사용 — 상세 → 뒤로 와도 다시 접수 안 함)
   ├ FALLBACK → 키워드 검색 결과 + 이유 안내(searchFallbackMessage) + 단어 칩
   └ PENDING  → useQuery ['event-search','request',id] 1초 폴링 → COMPLETED(AI 조건 칩 + 결과) / FALLBACK
                 화면 대기 25초(서버 마감 15초 + 여유) 넘게 PENDING → "다시 검색" / 접수·조회 실패 → 에러 + [다시 검색]
```
- **대체 검색은 서버가 한다** — 화면은 status(PENDING/COMPLETED/FALLBACK)만 보고 그린다. FALLBACK도 정상 결과(경고 색 안내만).
- 검색어를 URL에: 뒤로 가기·공유·새로고침에 같은 검색. 입력칸은 URL이 바뀌면 따라간다.
- 접수(POST)를 mutation이 아니라 useQuery로: 데이터를 바꾸지 않는 조회이고, 질문별 캐시로 같은 질문 재접수(한도·비용)를 막는다.
- 조건 칩(utils/searchFilter.describeFilter): 단어 "커피", 상태(StatusBadge의 STATUS_LABELS, 여러 개면 "또는"), 기간은 [from, to)를
  양끝 포함 날짜로(to - 1ms → "이번 주에 끝나는" = "11월 9일 ~ 11월 15일"), 남은 수량. 빈 조건이면 "조건 없이 전체".
- 결과 카드는 목록과 같은 EventList. 모바일은 입력칸 한 줄 + 버튼 아래 줄.
- 테스트: pages/EventSearch.test.tsx(조건 표시·폴링·접수 FALLBACK·폴링 TIMEOUT·결과 없음/전체 보기·빈 조건·에러 재시도·화면 타임아웃·빈 검색),
  utils/searchFilter.test.ts(날짜는 PC 시간대 0시로 만들어 시간대와 무관).

#### 챗봇 창 (7단계 C4, components/ChatbotPanel + hooks/useChatbot + api/chatbot)
```
헤더 [AI 챗봇] (로그인 시만) → ChatbotPanel (App 이 user.id 를 key 로 렌더 — 회원이 바뀌면 상태 초기화)
  대화방 고르기: sessionStorage coupon-rush:chatbot-session:{회원 ID} → 없으면 GET /api/chatbot/sessions 의 최근 방 → 없으면 첫 질문 때 POST 로 만듦
               저장한 방이 404(CHATBOT_SESSION_NOT_FOUND)면 잊고 최근 방을 다시 고른다
  질문: POST .../turns (202) → 대화방 GET 다시 → 마지막 턴이 PENDING 이고 마감 + 15초 전이면 1초마다 GET (그 밖엔 폴링 없음)
```
- 대화 내용은 Query(['chatbot','sessions',id])에만, 화면 state는 "보는 방 ID"와 "[새 대화] 눌림"뿐. 화면이 따로 타임아웃을 판단하지 않는다 —
  서버가 마감(60초) 뒤 조회 때 TIMEOUT으로 끝내므로 그 응답을 그린다 (마감 + 15초는 서버가 응답하지 않을 때의 안전장치).
- 답 기다리는 중에는 보내기·[새 대화]·[다시 보내기]를 막는다(서버도 409 CHATBOT_TURN_IN_PROGRESS). 연타는 inFlight ref로 렌더 전에 막는다.
- **답 속 주소 → 링크(linkify)**: HTML로 넣지 않고 글자 조각 + `<Link>`/`<a>` 요소로만 만든다(dangerouslySetInnerHTML 금지 — 답에 무엇이 와도 스크립트가 안 됨).
  같은 사이트(origin) 주소는 `<Link>`로 화면 이동 후 창을 닫고, 바깥 주소는 새 탭 + noopener.
- 메시지: api/errors.ts의 CHATBOT_* (접수 거절)와 chatbotFailureMessage(FAILED 사유). Enter = 보내기, Shift+Enter = 줄바꿈, 한글 조합 중 Enter 무시, Esc = 닫기.
- 플로팅 버튼이 아니라 헤더 버튼: 모바일 상세의 sticky [발급 받기]를 가리지 않게. 모바일(600px 이하)은 창이 전체 화면.
- 테스트(ChatbotPanel.test.tsx): 비로그인 버튼 없음, 첫 질문(방 생성 → PENDING 동안 보내기 막힘 → 답·내부 링크), 최근 방 이어 보기, 저장한 방 404 → 최근 방,
  실패 사유·다시 보내기(retryable만), 접수 거절 메시지, 새 대화, 닫기/Esc, linkify가 HTML을 글자로 둠.
- 테스트 메모: EventPages "중복 클릭" 테스트의 `expect(button).toBeDisabled()`가 클릭 직후 동기 검사라 App이 useAuth를 읽게 되자 깨졌다 —
  버튼 모양은 다음 렌더에서 바뀌므로 waitFor로, 중복 요청 방지는 requestIssue 1회 호출로 검증한다.

#### 이벤트 채팅 (7단계 D4, components/WebChatPanel + hooks/useWebChat + api/webChat)
```
상세 화면 (로그인 확인이 끝난 뒤 — loading 중엔 그리지 않음, key = 이벤트 ID)
  connectWebChat(eventId, 로그인?) ─ beforeConnect: 로그인이면 POST /api/web-chat/tickets → CONNECT ticket 헤더 (실패하면 읽기만으로 연결)
    onConnect → SUBSCRIBE /topic/events/{id} (+ 로그인 연결이면 /user/queue/errors) → 연결 횟수 +1
  연결 1회차 → 지난 대화 useInfiniteQuery ['web-chat','history',id] (첫 페이지 = 최신 50, [이전 대화 보기] = beforeId)
  2회차 이상(다시 연결) → 지난 대화 refetch (pub/sub 은 끊긴 동안 메시지를 남기지 않지만 DB 에는 있다)
  화면 = 지난 대화(오래된 페이지부터) + 실시간 메시지, messageId 로 한 번만 (mergeMessages)
```
- **STOMP 연결은 api/webChat.ts 안에만**(connectWebChat → {send, close}) — 훅·화면은 stompjs를 모르고, 테스트는 이 함수 하나만 대역으로 바꾼다.
  test/setup.ts가 모든 테스트에서 connectWebChat·fetchWebChatHistory를 vi.fn으로 막는다(상세 화면이 실제 WebSocket을 열지 않게). 연결 코드 테스트는 vi.unmock + stompjs 대역.
- 티켓은 **연결할 때마다 새로**(beforeConnect — 1회용·30초). beforeConnect 안 예외는 stompjs가 연결·재연결을 멈추므로 전부 안에서 잡는다.
  티켓 거절(만료)은 서버 ERROR + 연결 종료 → 자동 재연결이 새 티켓으로. 로그인 상태가 바뀌면(userId) 연결을 새로 맺는다.
- 재연결: 지수 백오프 1초 → 30초(서버 재시작 때 모든 탭이 동시에 두드리지 않게), 하트비트 10초(서버와 같게).
- 지난 대화 조회는 apiClient가 아니라 맨 axios — 공개 API라 JWT를 채팅 서버에 보내지 않는다(채팅 서버는 JWT를 모르는 서비스).
  staleTime Infinity·창 포커스 재조회 끔(다시 읽는 때 = 다시 연결됐을 때), gcTime 0(다른 이벤트에 갔다 오면 새로).
- 실시간 메시지는 소켓에서만 오므로 state(최근 500개). 서버 데이터 규칙(Query로만)의 예외 — 지난 대화는 Query.
- 보내기: 앞뒤 공백 제거, 글자 수는 코드 포인트(서버와 같은 기준, textarea maxLength는 UTF-16이라 안 씀), 450자부터 글자 수 표시, 500 넘으면 버튼 꺼짐.
  내 메시지는 따로 그리지 않고 방 구독으로 돌아온 것을 그린다(서버가 정한 순서·시각 그대로). 거절은 /user/queue/errors → webChatRejectionMessage, 다음 보내기 때 지움.
- 스크롤: 맨 아래(48px 안)를 보고 있을 때만 새 메시지를 따라 내려감, 내가 보내면 항상 맨 아래, [이전 대화 보기]는 붙이기 전 "맨 아래까지 거리"를 지켜 보던 위치 유지(useLayoutEffect).
  "위로 올리면 자동 로드" 대신 버튼 — 단순하고 테스트 가능(jsdom에 IntersectionObserver 없음).
- 본문은 글자로만(React 텍스트 노드, white-space: pre-line). 내 메시지 판단 = senderId === 로그인 회원 ID.
- **Vite 프록시 순서**: 적은 순서대로 처음 맞는 규칙(vite 6 for...in) → /api/web-chat/rooms·/ws/web-chat(ws: true)을 /api보다 먼저. changeOrigin 끔 —
  채팅 서버가 Origin(5173)을 허용 목록과 비교하고, 프록시는 브라우저 Origin을 그대로 넘긴다. /api/web-chat/tickets 는 8080 그대로.
- 한계: 끊긴 동안 50개가 넘게 오가면 다시 읽은 최신 페이지와 그 전 사이가 빌 수 있다(위로 [이전 대화 보기]를 누르면 채워짐). 구독 SUBSCRIBE가 서버에 등록되기 전
  수 ms 사이의 메시지는 놓칠 수 있다(RECEIPT로 기다리면 없앨 수 있으나 단순 브로커가 SUBSCRIBE 영수증을 보장하지 않아 보류).

#### 발급 폴링 구조 (hooks/useIssueCoupon)
```
[발급 받기] ─ useMutation: POST /api/coupons/{id}/issue
   ├─ 409/400/503 → rejected(code)      즉시 거절 (Redis 판정). 소진·이미 발급·기간 아님은 재시도 버튼 없음
   ├─ 201 (sync 모드) → completed       폴링 없음
   └─ 202 + requestId → pending
         └─ useQuery(['issue-requests', id]) refetchInterval:
              PENDING(또는 첫 응답 전) → 1초 후 다시 / COMPLETED·FAILED → false (중단)
              ├─ COMPLETED → completed  → ['events'], ['my-coupons'] 무효화
              ├─ FAILED    → failed(reason)  DB 저장 실패, Redis는 보상됨 → 다시 시도 가능 (COUPON_ALREADY_ISSUED 사유는 제외)
              ├─ 30초 동안 PENDING → timeout   폴링 중단 + "내 쿠폰에서 확인" (서버는 계속 처리 중일 수 있어 재시도 버튼 없음)
              └─ 조회 자체 실패(재시도 2회 후) → lost
```
- 폴링을 고른 이유: 결과는 보통 수백 ms~수 초 안에 나오고 한 사람당 한 번이라 1초 폴링으로 충분 (SSE/WebSocket은 연결 유지 비용·인프라가 더 든다).
  대규모 트래픽에서는 간격 백오프나 푸시로 바꾸는 것이 개선 후보.
- 상태는 서버 데이터(mutation + query)에서 **계산**한다 — 별도 useState로 결과를 복사하지 않는다. 화면 상태는 requestId, timedOut, inFlight뿐.
- 중복 클릭 방지: useRef(inFlight)로 렌더링 전 연타까지 막고(isPending은 다음 렌더에서야 바뀜), 버튼은 submitting/pending/최종 결과에서 disabled.
- 타임아웃 타이머는 202를 받은 순간부터 잰다 (의존성은 "끝났는지"만 — pollStatus로 두면 첫 응답 때 타이머가 다시 시작되는 버그가 있었다).
- 간격·타임아웃은 ISSUE_POLLING 객체 (화면 테스트에서 짧게 바꿈).
- **오픈 시각**: useServerNow(serverTime)로 서버-PC 시계 차이를 보정한 "지금"을 1초마다 계산 → statusAt(event, now)로 상태를 다시 계산해
  카운트다운과 버튼 활성화를 서버 시각에 맞춘다 (PC 시계가 틀려도 됨, 최종 판정은 서버). 상세는 3초마다 다시 조회(남은 수량), 0이면 누르기 전부터 "소진".

#### 실행 방법
1. `cd infra; docker compose up -d` (Postgres 5432, Redis 6379, Kafka 9092)
2. `cd backend; .\gradlew :coupon-api:bootRun` → 8080 (infra/.env를 읽음)
3. `cd frontend; npm install; npm run dev` → http://localhost:5173 (프론트는 환경변수 없음, 프록시 대상은 vite.config.ts의 BACKEND)
- 테스트 / 빌드: `npm test` / `npm run build` (tsc 타입 검사 + vite build). 자세한 순서·환경변수는 README.md.

#### 테스트 메모
- Vitest + Testing Library + jsdom. client.test.ts는 axios-mock-adapter로 인터셉터 검증.
- vitest 4 (2026-11-25, 3 → 4): restoreMocks가 vi.spyOn만 되돌려서 vite.config.ts에 mockReset: true를 함께 둔다(빼면 vi.fn 호출 기록이 다음 테스트로 넘어가 23개 실패).
  타입 없는 vi.fn()은 함수 타입 자리에 못 넣는다 → vi.fn<타입>() (api/webChat.test.ts).
  AuthFlow / EventPages / AdminPages / AppShell(에러 바운더리·404)은 api 모듈을 vi.mock으로 대체 (test/renderApp.tsx: Provider 구성 + apiError 헬퍼).
- routes.test.ts는 node 환경(esbuild가 jsdom에서 안 돎).
- hooks/useIssueCoupon.test.tsx는 가짜 시계로 실제 값(1초 간격·30초 타임아웃) 검증. 가짜 시계에서는 TanStack Query의 notifyManager
  (setTimeout 0 배치)가 밀려 캐시는 바뀌었는데 리렌더가 안 되므로 notifyManager.setScheduler(cb => cb())로 바꾸고 afterEach에서 되돌린다.
- React는 자식 effect를 부모보다 먼저 실행한다 (콜백 페이지의 refresh가 AuthProvider 복원보다 먼저 호출됨).
  실제로는 single-flight라 같은 결과를 공유하지만, mock으로 호출 순서별 응답을 다르게 주면 테스트가 엇갈린다.
- 실제 소셜 로그인(제공자 화면)은 자동 테스트하지 않는다 — 수동 확인.

### LLM 메시지 계약 (7단계) — backend/libs/llm-contract, 프로젝트 경로 :llm-contract
순수 Java(Spring 없음, jackson-annotations만). coupon-api와 llm-service가 함께 의존하는 **유일한 공유 모듈**.

| 토픽 | 방향 | 키 | 값 |
|---|---|---|---|
| `llm-requests` | coupon-api → llm-service | requestId | `LlmRequestMessage(schemaVersion, requestId, type, requestedBy, requestedAt, deadline, regenerate, eventCopy, eventSearch)` |
| `llm-results` | llm-service → coupon-api | requestId | `LlmResultMessage(schemaVersion, requestId, status, candidates, searchFilter, failureReason, model, inputTokens, outputTokens, cached, latencyMs, completedAt)` |
| `llm-requests.DLT` | | | 역직렬화 불가 요청만. LLM 실패는 FAILED 결과로 돌려준다 (재시도 = 비용) |
| `llm-chatbot-requests` (C2) | coupon-api → llm-service | requestId | `LlmRequestMessage`(type CHATBOT만). 그룹 llm-service-chatbot, 전용 스레드 — 아래 "챗봇 (C2)" |
| `llm-chatbot-requests.DLT` | | | 챗봇 요청 중 역직렬화 불가 (에러 핸들러가 원래 토픽으로 DLT를 고른다) |

- 진화 규칙: 필드는 **추가만** (모든 레코드 @JsonIgnoreProperties(ignoreUnknown=true) → 먼저 배포된 쪽이 새 필드를 무시).
  이름 변경·삭제·의미 변경은 SCHEMA_VERSION을 올린다. 받는 쪽은 LlmTopics.isSupported(version)가 false면 처리하지 않는다.
  enum 값 추가는 구버전 소비자에서 역직렬화 실패(→ DLT)가 되므로 소비자부터 배포.
- 레코드 compact 생성자가 규칙을 검사한다 → 깨진 메시지는 **읽는 순간 예외** (LLM을 부르기 전에 걸러짐).
  COMPLETED = 후보 1~3개·사유 없음 / FAILED = 사유 필수·후보 없음, 토큰 ≥ 0, deadline > requestedAt, EVENT_COPY면 eventCopy 필수.
- 길이 상한도 계약에: keywords ≤ 200(프롬프트 비용), 후보 name ≤ 100 / description ≤ 500 (= coupons 컬럼 길이, 그대로 저장 가능).
- LlmFailureReason 하나에 llm-service 사유(LLM_UNAVAILABLE, BUDGET_EXCEEDED, INVALID_OUTPUT, CONTENT_REFUSED)와
  coupon-api 사유(TIMEOUT, PUBLISH_FAILED)를 함께 둔다 — DB와 화면 메시지가 한 목록을 기준으로. isRetryable = [다시 시도] 노출 기준.
- 실패 결과에도 쓴 토큰을 싣는다 (INVALID_OUTPUT처럼 호출 후 실패해도 비용은 나갔으므로 예산에 잡아야 함).
- 자연어 검색(A1, 2026-11-10): 요청은 type에 맞는 입력 **하나만**(eventCopy XOR eventSearch, 둘 다면 거절).
  결과에는 type이 없다 — coupon-api가 requestId로 자기 행의 type을 안다. 대신 COMPLETED는 candidates(1~3) XOR searchFilter.
  LLM은 **검색 조건만** 만들고 조회는 coupon-api DB가 한다(없는 이벤트를 지어낼 수 없음). 조건은 모두 AND, 빈 조건 = 제한 없음,
  전부 비어도 COMPLETED(isEmpty, JSON에는 안 실림 — 어떻게 보여 줄지는 coupon-api가 정함). 기간은 발급 기간 기준 [from, to).
  "이번 주" 같은 상대 시각은 requestedAt(한국 시각) 기준 — 기준 시각 필드를 따로 두지 않음. 검색은 regenerate 항상 false.
  새 필드는 끝/중간에 추가했지만 JSON은 이름으로 읽으므로 호환 (이전 메시지 = null). 단 **레코드 전체 생성자를 직접 쓰는 테스트는 인자 추가 필요**.
- 챗봇(C1, 2026-11-19): LlmRequestType.CHATBOT + 요청 필드 chatbot(ChatbotInput: message ≤ 500, history ≤ 10턴 — ChatbotTurn(question, answer)),
  결과 필드 chatbotReply(ChatbotReply: text ≤ 2000). **CHATBOT은 requestedBy ≥ 1만**(생성자 검사) — "내 쿠폰"을 조회하는 기능이라 비로그인 0 금지,
  C2에서 requestedBy가 mcp-server X-Mcp-User-Id가 된다(누구인지는 문장이 아니라 JWT). 새 필드는 두 레코드의 **끝**에 추가(기존 생성자 인자 순서 유지).
  COMPLETED는 candidates / searchFilter / chatbotReply 중 정확히 하나. regenerate·cached는 챗봇에서 항상 false.
  enum 값 추가라 **llm-service부터 배포** — 컨테이너로 띄우면 `--build` 필수(구버전은 CHATBOT을 못 읽어 DLT → 60초 후 TIMEOUT).
- **SCHEMA_VERSION 2 (2026-11-21, 이름 정리)**: CHAT → CHATBOT, 필드 chat → chatbot, chatReply → chatbotReply, ChatInput/ChatTurn/ChatReply → Chatbot*,
  토픽 llm-chat-requests → llm-chatbot-requests. 문구 생성·검색은 v1과 형식이 같아 isSupported(1)도 true(테스트로 고정). v1의 CHAT 메시지만 못 읽어 DLT →
  **coupon-api·llm-service를 함께 다시 띄우고, 컨테이너 llm-service는 `--build`**. DB의 기존 CHAT 행은 V9가 CHATBOT으로 바꾼다.
- LlmContractJsonTest: 왕복 직렬화, **필드 이름 고정**(바꾸면 실패 — 의도한 변경이면 버전과 함께 고친다), 고정 JSON 읽기,
  모르는 필드 무시(FAIL_ON_UNKNOWN_PROPERTIES=true인 엄격한 mapper로 검증), 빠진 선택 필드 기본값, 규칙 위반 거절.

### LLM 요청 (7단계, coupon-api 쪽) — com.couponrush.llm, V5 llm_requests
```
POST /api/admin/llm/event-copy (ROLE_ADMIN, {keywords, tone, totalQuantity, issueStartAt?, issueEndAt?, regenerate})
  ① 레이트 리밋 Lua (사용자별 분 5 / 일 50, Asia/Seoul 창) → 초과면 429, 행·메시지 없음
  ② llm_requests INSERT PENDING (deadline = now + 60s) — 발행 "전에" 커밋 (결과가 행보다 먼저 오면 조건부 UPDATE 0행 → 결과 유실)
  ③ llm-requests 발행 (키 requestId), ack 최대 5초 → 실패면 FAILED(PUBLISH_FAILED) + 503 LLM_REQUEST_UNAVAILABLE
  ④ 202 + Location /api/admin/llm/requests/{id}
llm-results 컨슈머 (그룹 coupon-api-llm-results, 리스너 llmResultListener, 전용 컨테이너 concurrency 1)
  → UPDATE ... WHERE request_id = ? AND status = 'PENDING'  (0행 = 중복·늦은 결과·모르는 요청 → 로그만, 예외 없음)
```
- **PENDING부터 DB에 쓴다** (발급은 Redis에만). 분당 몇 건이라 부담이 없고 조회·타임아웃 정리가 DB 한 곳에서 끝난다.
- 상태 전이는 LlmRequestRepository의 조건부 UPDATE(native)뿐 — 결과·타임아웃이 동시에 와도 행마다 한쪽만 반영(LlmResultHandlerTest 경합 테스트).
  CHECK 제약: COMPLETED ⇒ result 있고 사유 없음, FAILED ⇒ 사유 있음, status/type/failure_reason 값 목록(FlywayMigrationTest가 enum과 비교).
- 타임아웃: 조회(GET) 때 마감이 지났으면 그 자리에서 TIMEOUT 확정 + LlmRequestTimeoutScheduler(30초, app.llm.timeout-sweep.*)가 아무도 안 보는 요청 정리.
  TIMEOUT 뒤에 온 결과는 버린다(비용은 이미 나감 → WARN 로그). 테스트에서는 스케줄러를 끄고 LlmRequestTimeouts.sweep() 직접 호출.
- 결과 컨슈머 에러: 읽을 수 없는 메시지(형식·계약 위반)는 건너뜀, DB 일시 장애는 1초×5회 후 포기(→ 마감 후 TIMEOUT). 결과용 DLT 없음.
  에러 핸들러는 Bean이 아니라 컨테이너 팩토리 안에서 생성 (발급 메인 CommonErrorHandler 자동 적용을 깨지 않게).
- 시각은 마이크로초로 자른다 (Windows Clock 100ns vs PostgreSQL µs → 메시지 deadline과 DB deadline_at이 달라지는 버그가 테스트에서 나왔음).
- 조회는 본인 요청만 (남의 requestId·없는 ID 모두 404 LLM_REQUEST_NOT_FOUND). requestedBy FK 없음(감사 기록).
- 에러 코드: LLM_RATE_LIMITED(429, 분), LLM_DAILY_LIMIT_EXCEEDED(429, 일), LLM_REQUEST_UNAVAILABLE(503), LLM_REQUEST_NOT_FOUND(404).
- Redis 키: llm:rate:{userId}:d:{yyyyMMdd} (TTL 2일), llm:rate:{userId}:m:{yyyyMMddHHmm} (TTL 2분). 거절된 요청은 세지 않는다.
  **같은 입력(캐시로 끝나는 요청)도 1회로 센다** — 2026-11-07 결정: 그대로 둔다. coupon-api는 캐시 키(모델·프롬프트 버전)를 몰라 접수 시점에 구분할 수 없고,
  한도는 비용뿐 아니라 요청 남발도 막는 용도. 대안(보류): 결과가 cached=true면 카운트 되돌리기 / 화면에서 직전 결과와 같은 입력이면 요청 안 함.
- 격리: coupon-api는 :llm-contract만 의존. LlmIsolationTest가 spring-ai / anthropic / openai / langchain4j가 클래스패스에 없음을 검사.
  LlmRequestApiTest가 결과 리스너의 그룹·컨테이너·에러 핸들러가 발급 리스너와 다른지 검사. 테스트 정리: llm_requests 행, llm:* 키.

### llm-service (7단계) — backend/llm-service, com.couponrush.llmservice, 포트 8081(헬스체크만)
```
llm-requests ─▶ LlmRequestConsumer (그룹 llm-service, concurrency 2 = 동시 LLM 호출 상한, max.poll.records 1)
                  └▶ LlmRequestProcessor
                       ① 모르는 schemaVersion → 건너뜀      ② llm:done:{id} 있으면 저장된 결과를 다시 발행 (LLM 0회)
                       ③ 마감 지남 → 건너뜀 (결과 없음)       ④ llm:inflight:{id} SET NX 실패 → 다른 작업자 처리 중, 건너뜀
                       ⑤ LlmClient 호출 → COMPLETED/FAILED 결과를 llm:done 에 저장 (TTL 1일) → 반환
                  └▶ llm-results 발행 (ack 대기) → 반환 후 오프셋 커밋
```
- **LLM 실패는 예외가 아니라 FAILED 결과**로 (LlmCallException → 사유 그대로, 예상 못 한 RuntimeException → LLM_UNAVAILABLE).
  예외를 올리면 Kafka 재시도가 LLM을 반복 호출 = 비용 반복.
- Kafka 에러 핸들러(이 앱의 유일한 CommonErrorHandler Bean): 읽을 수 없는 메시지(InvalidLlmRequestException)는 재시도 없이 llm-requests.DLT,
  Redis·결과 발행 일시 장애는 1s→2s→4s 후 DLT. 재처리돼도 ②에서 끝나 LLM은 다시 안 부른다.
- 결과를 Redis(llm:done)에 저장한 **다음에** 발행 — 발행 실패 후 재처리가 LLM 재호출이 되지 않게. Kafka 오프셋만으로는 at-least-once라 부족.
- max.poll.interval.ms 5분: LLM 호출이 길어 poll이 늦어지면 리밸런스 → 같은 메시지 중복 처리·중복 비용.
- DB 없음, 사용자 JWT 없음, 공개 API 없음. 로컬 bootRun은 infra/.env 전체를 읽지만 쓰는 값은 Kafka·Redis(·4단계부터 LLM_*)뿐.
- LlmClient 구현 2개 — **fake = 미리 만든 코드 규칙, anthropic = Claude(정상 동작)**. 흐름은 같고 "결과를 누가 만드느냐"만 다르다.
  | 기능 | fake (기본, LLM 호출 없음) | anthropic (LLM_PROVIDER=anthropic + LLM_API_KEY) |
  |---|---|---|
  | 문구 생성 | FakeLlmClient.generateEventCopy — 톤별 고정 문장 3개(계약 길이로 자름) | event-copy.v1.txt + EventCopyOutput → EventCopyOutputValidator |
  | 자연어 검색 | FakeEventSearch — 정해진 표현만 인식, 나머지 단어 → keywords | event-search.v1.txt + EventSearchOutput → EventSearchOutputValidator |
  fake 토큰은 글자 수로 흉내(예산 로직 검증용), app.llm.fake.delay로 응답 지연(로컬 화면 확인용). fake 결과는 품질이 아니라 흐름 확인용 —
  사용자에게 설명할 때 이 구분을 분명히 한다. 같은 설명이 README "2-1. LLM 서비스"와 LlmClient 주석에도 있다 (바꾸면 셋 다).
- 챗봇(CHATBOT): 처리기가 LlmClient 대신 **chatbot/ChatbotResponder**(= ChatbotOrchestrator)에 맡긴다 — 아래 "챗봇 (7단계 C2)" 메모.
  (LlmClient의 "한 번 호출" 모양이 아니라 모델 ↔ 도구를 여러 번 오가서 인터페이스를 따로 둠)
  **챗봇은 응답 캐시를 읽지도 쓰지도 않는다** — 답이 지난 대화·"내 쿠폰"처럼 사람·시점마다 다른 조회에 기대서, 같은 문장에 같은 답을 주면 남의 정보가 샐 수 있다.
  예산 확인·사용량 기록·멱등(llm:done)·마감 건너뛰기는 다른 기능과 같다 (CostControlFlowTest 챗봇 3개).
- 테스트(LlmRequestFlowTest): Kafka·Redis 컨테이너만. coupon-api 대신 llm-requests 발행, llm-results 직접 읽기.
  @MockitoSpyBean FakeLlmClient로 호출 횟수 검증 (재전달 1회, 만료·모르는 버전·처리 중·DLT는 0회).
  "처리하지 않음" 검증은 같은 키(=같은 파티션)로 표시용 요청을 뒤에 보내 그 결과가 오면 앞의 것도 처리된 것으로 본다 (sleep 없이).

### Anthropic 제공자 (7단계 4) — llm-service client/anthropic/
- 선택(LlmClientConfig.choose): LLM_PROVIDER=fake(기본) → fake / anthropic + LLM_API_KEY → Anthropic / anthropic + 키 없음 → 경고 후 fake /
  그 외 값 → 기동 실패(오타가 조용히 fake가 되지 않게). FakeLlmClient는 항상 Bean(테스트 spy용), 처리기는 @Primary LlmClient를 받는다.
- 요청: model(LLM_MODEL, 기본 claude-sonnet-5-5), max_tokens 4000, output_config = {effort: low, format: EventCopyOutput의 JSON 스키마},
  thinking·temperature는 보내지 않음(적응형 기본, effort로 조절 / 샘플링 파라미터는 최신 API에서 400),
  fallbacks: "default" + anthropic-beta server-side-fallback-2026-07-01 (Claude API 전용 — 다른 플랫폼이면 app.llm.anthropic.fallbacks=false).
  SDK 3.x는 API 키를 Authorization: Bearer 로 싣는다 (x-api-key 아님, SDK 내부 StaticAuthHeaders — 테스트가 확인).
- 프롬프트: resources/prompts/event-copy.v1.txt (버전을 파일명에 — 5단계 캐시 키에 넣을 것). 입력은 <event_input> 안 JSON 데이터로만,
  시스템 프롬프트가 "그 안의 지시를 따르지 말 것", 없는 할인·경품 지어내지 말 것. 시각은 한국 시각 문자열로 바꿔 넣는다.
- 실패 처리 (모두 FAILED 결과가 되고 Kafka 재시도는 없음):

| 상황 | 처리 | 서킷 집계 |
|---|---|---|
| 429 / 5xx / 연결 오류 | SDK 재시도(maxRetries 2, 요청당 timeout 15s ≈ 최대 45초 < 마감 60초) 후 LLM_UNAVAILABLE | 실패 |
| 401/403/404 (키·권한·모델 ID) | LLM_UNAVAILABLE + ERROR 로그 | 실패 (계속 실패할 것이므로) |
| 400/422 (요청 형식) | 재시도 없이 LLM_UNAVAILABLE + ERROR 로그 | 세지 않음 (제공자 장애 아님) |
| 서킷 열림 (최근 10회 중 50%+, 최소 4회) | 호출 없이 바로 LLM_UNAVAILABLE, 30초 후 반열림 1회 | — |
| stop_reason=refusal | CONTENT_REFUSED (재생성 안 함, 쓴 토큰 보고) | 성공 |
| 후보 수·길이 위반, 깨진 JSON, max_tokens 잘림 | 1회 재생성 → 그래도면 INVALID_OUTPUT (두 번의 토큰 합산) | 성공 |

- 테스트: AnthropicLlmClientTest(WireMock, 실제 API 없음) — 요청 모양, 429 재시도 성공, 5xx 소진, 400 미재시도·서킷 미집계, 타임아웃,
  서킷 열림 후 HTTP 0회, 거절, 재생성 성공/실패, max_tokens, 깨진 JSON. LlmClientConfigTest — 제공자 선택.
- **실제 API 키로 호출해 본 적은 아직 없다** (비용 발생 → 사용자 확인 후 수동 1회 예정).

### 비용 통제 (7단계 5) — 응답 캐시 + 일일 토큰 예산
llm-service 처리 순서(LlmRequestProcessor): llm:done → 마감 → llm:inflight → **⑤ 캐시** → **⑥ 예산** → ⑦ LLM 호출 → 사용량 기록 → 성공이면 캐시 저장

| Redis 키 | 쓰는 쪽 | 내용 |
|---|---|---|
| `llm:cache:{sha256}` | llm-service | 성공 응답(후보 + 모델), TTL 24h. 키 = sha256(client.cacheIdentity() + 정규화 입력) |
| `llm:usage:{yyyyMMdd}` (hash, 한국 날짜, TTL 8일) | llm-service 기록 / coupon-api 읽기 | total_tokens(예산 기준), input_tokens, output_tokens, calls, cache_hits, budget_rejections |

- 캐시 키의 cacheIdentity: fake → `fake:v1`, Anthropic → `anthropic:{model}:{event-copy.v1}:effort={low}` — 모델·프롬프트 버전·effort가 바뀌면 예전 응답을 안 쓴다.
  입력 정규화: 키워드 공백 정리 + 소문자. 키는 해시(관리자 입력이 키 이름에 드러나지 않게). 실패 결과는 캐시하지 않는다(일시 장애가 24시간 가지 않게).
  regenerate=true("다시 생성")는 캐시를 읽지 않고, 새 결과로 덮어쓴다. 캐시 응답 = COMPLETED + cached=true + 토큰 0.
- llm-service는 캐시를 예산보다 먼저 본다 → 처리 중에 예산이 바닥나도 캐시 응답은 준다(비용 0).
  단 **이미 소진된 뒤의 새 요청은 coupon-api LlmBudgetGuard가 접수 단계에서 먼저 막으므로 캐시까지 가지 않는다**
  (coupon-api는 캐시 키를 모른다 — 프롬프트 버전·모델은 llm-service만 앎. 소진 후 캐시 허용이 필요하면 가드를 빼고 llm-service 판정에 맡기면 된다).
- 예산: 호출 전 total_tokens < 예산이면 호출, 호출 후 실제 사용량 HINCRBY (실패해도 쓴 토큰은 기록 — INVALID_OUTPUT 등).
  **동시 처리 수(consumer concurrency 2)만큼 예산을 살짝 넘을 수 있다** — 최대 토큰을 미리 예약하면 남은 예산이 실제보다 훨씬 적게 보여 정상 요청까지 막혀서 택하지 않음.
  초과 → LLM 호출 없이 FAILED(BUDGET_EXCEEDED, 재시도 불가 표시). Redis 장애로 예산 확인이 안 되면 호출하지 않는다(예외 → Kafka 재시도 → DLT).
  캐시·사용량 "기록" 실패는 로그만 (결과를 버리지 않음).
- coupon-api LlmBudgetGuard: 같은 키를 읽어 이미 소진이면 접수 단계에서 429 LLM_BUDGET_EXCEEDED (레이트 리밋 횟수·행·메시지를 쓰지 않음, 레이트 리밋보다 먼저).
  키 규칙은 계약 모듈 LlmUsageKeys(두 서비스 공유). 예산 값은 두 서비스가 같은 LLM_DAILY_TOKEN_BUDGET을 읽는다.
- 테스트: CostControlFlowTest(실제 Kafka·Redis, fake) — 캐시 적중·공백 차이·다시 생성·실패 미캐시, 토큰 기록·실패 토큰·예산 초과·49,999에서 호출·소진 후 캐시 제공.
  ResponseCacheKeyTest(키 규칙), LlmUsageKeysTest(한국 날짜 경계), coupon-api LlmRequestApiTest(429 LLM_BUDGET_EXCEEDED).

### 자연어 검색 (7단계 A2, llm-service) — client/FakeEventSearch, client/anthropic/EventSearch*, prompts/event-search.v1.txt
- 처리기는 type별로 캐시 키·LLM 호출·결과(completed / searchCompleted)만 고른다. 마감·멱등·예산·캐시·실패 처리 흐름은 문구 생성과 같다.
- **기준 날짜 = 접수 시각(requestedAt)의 한국 날짜**(처리 시각 아님, KstDates). 모델에게도 날짜·요일만 주고 시각은 주지 않는다.
  범위는 날짜 경계(0시)로만 — 같은 날 같은 질문이면 같은 조건 → 캐시 가능. "지금 진행 중"은 시각 범위가 아니라 OPEN 상태로.
- 캐시 키 = sha256(cacheIdentity(EVENT_SEARCH) + 정규화 질문 + 기준 날짜). 날짜가 바뀌면 "이번 주"의 뜻이 달라져 다른 키.
  LlmClient.cacheIdentity(type): 문구는 기존 값 그대로(fake:v1, …event-copy.v1…)라 기존 캐시 유지, 검색은 fake:search.v2(규칙 변경 때 올림) / …event-search.v1….
  ResponseCache.Entry(candidates, searchFilter, model) — 예전 캐시 값은 searchFilter=null로 읽힘. 쓸 수 없는 캐시 값은 실패가 아니라 캐시 없음으로 처리.
- Anthropic: 출력 형식 EventSearchOutput은 계약과 일부러 다르다 — 기간은 **한국 날짜 문자열 YYYY-MM-DD, 양끝 포함**(모델에게 "이번 주 = 월~일"이
  자연스럽고 시간대 계산을 맡기지 않음), "조건 없음"은 null 대신 빈 문자열·빈 목록(스키마 필드가 모두 필수). EventSearchOutputValidator가
  [from 0시, to+1일 0시) KST로 바꾸고, 단어 공백·빈 값·중복은 고쳐 받고, 단어 수/길이 초과·날짜 형식 오류·뒤집힌 범위는 거절(→ 1회 재생성 → INVALID_OUTPUT).
  재시도·서킷·거절·재생성 루프는 AnthropicLlmClient.generate 하나를 두 기능이 공유 (프롬프트·출력 형식·검증기만 다름).
- fake 규칙: "진행 중" → OPEN, "오픈 예정"·"시작 전" → UPCOMING, "종료된"·"끝난" → ENDED("끝나는"은 기간), "남은"·"재고" → availableOnly,
  오늘/내일/이번 주/다음 주/이번 달 → "시작·오픈·열리는"이 있으면 시작 범위, 아니면 종료 범위. 나머지 단어 → keywords(조사·일반어 제거, 5개·50자).
  "지금"·"현재"·"요즘"·"당장"은 키워드에서 뺀다(AI 조건은 키워드 ALL이라 남으면 진행 중 이벤트가 걸러짐 — 2026-11-16 수정).
  한 주는 월요일 시작. 실제 모델 품질이 아니라 흐름 확인용.
- 테스트: FakeEventSearchTest, EventSearchOutputValidatorTest, AnthropicLlmClientTest(검색 요청 모양·성공·재생성 후 INVALID_OUTPUT·캐시 식별자),
  ResponseCacheKeyTest(날짜별 키), LlmRequestFlowTest(검색 → COMPLETED + 조건), CostControlFlowTest(검색 캐시, 문구 캐시와 안 섞임).

### 자연어 검색 (7단계 A3, coupon-api) — com.couponrush.search, coupon/search/
```
POST /api/coupons/search {query 1~200자} (공개, 로그인하면 회원별 / 아니면 IP별 한도)
  ① 예산 소진(LlmBudgetGuard) / 검색 한도 초과(분 10·일 100) / 발행 실패 / 그 밖의 예외 → 그 자리에서 키워드 검색 → 200 FALLBACK(사유)
  ② llm_requests PENDING(type EVENT_SEARCH, requested_by = 회원 ID 또는 0, 마감 15초) → llm-requests → 202 PENDING + Location
GET /api/coupons/search/{id} → PENDING / COMPLETED(AI 조건 filter + DB 결과) / FALLBACK(TIMEOUT·LLM_UNAVAILABLE·BUDGET_EXCEEDED + 키워드 결과)
```
- **검색은 AI 없이도 동작한다** — LLM 쪽 문제는 에러가 아니라 FALLBACK + 사유(SearchFallbackReason)로 응답. 400은 입력 검증뿐.
- AI는 조건만, 조회는 항상 coupon-api DB: CouponService.search(CouponSearchCondition) — 목록과 같은 정렬(오픈 최신순)·50개·응답 형식.
  CouponSearchCondition은 coupon 패키지 표현(계약에 의존 안 함), 변환은 EventSearchService.toCondition (상태 이름 = CouponStatus).
- CouponSpecifications(Criteria): 단어는 이름·설명 부분 일치(lower, LIKE 이스케이프 — "50%"가 와일드카드가 되지 않게), AI 조건은 ALL·키워드 검색은 ANY,
  상태는 조회 시각 기준(CouponStatus.of와 같은 [start, end)), 기간 [from, to). availableOnly는 SQL(DB 카운터 < 총 수량)로 1차,
  Redis 재고로 2차 (DB 카운터는 늦게 따라오므로 "DB로 소진"만 확실 — 남은 수량 표시와 같은 기준).
- 키워드 검색(KeywordQuery): 공백 분리 → 문장부호·끝 조사 하나 제거(2자 이상 남을 때) → 1글자·일반어(쿠폰, 이벤트, 보여줘…) 제외 → 5개·50자. 뜻 해석 없음.
- 결과 저장: llm_requests.result = 검색 조건 JSON (LlmResultHandler가 searchFilter가 있으면 그것을, 아니면 후보를 저장).
  관리자 조회 API(/api/admin/llm/requests/{id})는 EVENT_COPY일 때만 후보를 읽는다(검색 행을 후보로 파싱하지 않게).
- 검색 조회는 소유자를 검사하지 않는다: 결과는 공개 이벤트 목록이고 UUID를 아는 것이 권한. 공개 경로라 만료 토큰이 와도 401 → refresh가 안 일어나서
  소유자 검사를 하면 본인 요청도 못 볼 수 있다. 문구 생성 요청 ID로 검색 조회하면 404 EVENT_SEARCH_NOT_FOUND.
- 비로그인 IP = getRemoteAddr. 리버스 프록시 뒤에 배포하면 server.forward-headers-strategy로 신뢰 프록시의 X-Forwarded-For를 쓰게 할 것
  (로컬은 Vite 프록시라 비로그인 전원이 127.0.0.1 한 한도를 나눠 씀). 한도 키: llm:search-rate:{user:ID|ip:주소}:d|m:… (문구 생성과 별도).
- 설정 app.llm.search.{request-timeout 15s, per-minute 10, per-day 100} (환경변수 없음). 마감이 짧아 실제 LLM이 늦으면 결과는 버려지고 비용은 나간다 (WARN 로그).
- 테스트: EventSearchApiTest(접수·PENDING·회원별 한도·400 / COMPLETED·빈 조건·관리자 조회 / LLM 실패·TIMEOUT·예산·한도 fallback / 404),
  CouponSearchTest(정렬·부분 일치·ALL/ANY·이스케이프·상태·기간 경계·Redis 재고·AND), KeywordQueryTest.
- 테스트 메모: 고정 날짜로 Redis 적재하는 테스트는 키 만료(종료 + 7일, 절대 시각)가 지나면 깨진다 — RedisCouponStockTest가 2026-11-13에 깨져 현재 시각 기준으로 바꿈.

### 챗봇 대화 (7단계 C1, coupon-api) — com.couponrush.chatbot, V8 chat_sessions / chat_turns → V9에서 chatbot_sessions / chatbot_turns로 이름 변경
```
POST /api/chatbot/sessions → 201 대화방     GET /api/chatbot/sessions → 내 대화방 20개(최근 순, title = 첫 질문 30자)
GET  /api/chatbot/sessions/{id} → 대화방 + 최근 턴 50개(오래된 것부터)
POST /api/chatbot/sessions/{id}/turns {message 1~500} (한 트랜잭션)
  ① 대화방 행 잠금(FOR UPDATE) + 소유자 확인(남의 방 404) → ② 최근 턴이 PENDING이면 409 CHATBOT_TURN_IN_PROGRESS
  ③ 예산(LlmBudgetGuard) → 챗봇 한도(llm:chatbot-rate:{userId}, 분 5·일 50) → ④ llm_requests PENDING(CHATBOT, 마감 60초) + chatbot_turns 저장 → 커밋
  ⑤ 커밋 후 llm-requests 발행(실패면 요청 FAILED(PUBLISH_FAILED) + 503 CHATBOT_UNAVAILABLE, 턴은 실패로 남음) → 202 PENDING 턴 + Location
GET  /api/chatbot/sessions/{id}/turns/{turnId} → PENDING / COMPLETED(answer) / FAILED(failureReason, retryable). 마감 지난 PENDING은 그 자리에서 TIMEOUT
```
- **턴 = 질문 + LLM 요청 하나**(chatbot_turns.llm_request_id UNIQUE FK). 답·상태·실패 사유·토큰은 llm_requests에만 — 답을 따로 저장하면
  결과 도착·타임아웃·늦은 결과·중복 결과마다 두 테이블이 어긋날 수 있어서. 결과 반영은 기존 LlmResultHandler 그대로(result = {"text"}).
- 왜 접수를 한 트랜잭션으로: 요청 행과 턴이 함께 생기거나 함께 없어야 하고, 발행은 커밋 뒤(결과가 행보다 먼저 오면 버려짐).
  그래서 LlmRequestService를 prepareChatbot(예산·한도·PENDING 저장, 호출자 트랜잭션 참여) / publish(커밋 후)로 나눴다.
- 방마다 기다리는 질문 1개: 답이 섞이거나 직전 답이 history에서 빠진 채 처리되지 않게. 동시 요청은 대화방 행 잠금으로 직렬화(동시 2개 → 202 + 409 테스트).
  답이 안 오면 마감(60초) 뒤 TIMEOUT이 되어 다시 질문할 수 있다. 다른 방은 막지 않는다.
- history = 답을 받은(COMPLETED) 최근 턴 최대 10개, 오래된 것부터 (최근 20턴을 살펴봄). 실패·대기 턴은 빼고 화면에는 보여 준다.
- 에러 코드는 챗봇용으로 바꿔 준다(CHATBOT_RATE_LIMITED / CHATBOT_DAILY_LIMIT_EXCEEDED / CHATBOT_BUDGET_EXCEEDED / CHATBOT_UNAVAILABLE) — 공통 LLM 코드 메시지가 "문구 생성" 기준이라.
  다시 보내기 = 새 턴(같은 턴을 재처리하지 않음). 프론트 메시지(api/errors.ts)는 C4에서.
- chatbot_sessions.user_id는 FK + ON DELETE CASCADE(대화는 회원 것), chatbot_turns → chatbot_sessions CASCADE, chatbot_turns → llm_requests는 RESTRICT.
  **테스트 정리 순서: chatbot_sessions → llm_requests → users** (턴이 남아 있으면 llm_requests를 못 지운다).
- 설정 app.llm.chatbot.{request-timeout 60s, per-minute 5, per-day 50} (환경변수 없음). 구조 규칙(ArchitectureTest): chatbot → llm은 허용, chatbot → search 금지, coupon·llm → chatbot 금지.
- 남은 것: 대화방 삭제·이름 바꾸기 없음, 오래된 대화 정리 없음, 대화방 수 상한 없음(한도로만 묶임).

### 챗봇 (7단계 C2, llm-service) — chatbot/, client/anthropic/AnthropicChatbotModel, prompts/chatbot.v1.txt
```
llm-chatbot-requests ─▶ LlmRequestConsumer.consumeChatbot (그룹 llm-service-chatbot, app.llm.chatbot.concurrency 2) ─▶ LlmRequestProcessor (멱등·마감·예산 동일, 캐시 없음)
  └▶ ChatbotOrchestrator.answer(회원, 질문+지난 10턴, 접수 시각, 마감)
       ① McpToolGateway.open(회원) — mcp-server initialize + tools/list (헤더 X-Mcp-Service-Token, X-Mcp-User-Id)
       ② ChatbotModel.start(질문, 도구 목록, 오늘 날짜) → next(toolsAllowed) ─┬ Answer → ChatbotReply (2000자로 자름, 빈 답 = INVALID_OUTPUT)
                                                                       └ ToolRequests → 도구 실행 → addToolResults → ②로
```
- **토픽을 나눈 이유(벌크헤드)**: 챗봇 질문 하나가 스레드를 수십 초 잡고, 그 안의 search_events가 다시 llm-requests(EVENT_SEARCH)를 만든다.
  같은 토픽·스레드면 챗봇이 기다리는 검색을 처리할 스레드가 없어(파티션이 그 스레드 담당이면 확정) 검색이 15초 마감 → 키워드 fallback이 된다.
  coupon-api가 type으로 토픽을 고른다(LlmTopics.requestTopicOf). 에러 핸들러는 하나(Bean)를 두 리스너가 공유, DLT는 원래 토픽별.
- **상한(질문 하나)**: 도구 최대 5회(넘는 호출은 실행하지 않고 "한도" 오류 결과 — 요청한 호출마다 결과가 하나씩 있어야 API가 받는다),
  토큰 20,000(모든 호출 합, 넘으면 다음 호출은 도구 없이), 마감 5초 전부터 새 호출 안 함(LLM_UNAVAILABLE). 한도에 닿으면 끊지 않고 도구를 끈 마지막 답 1회
  → 실제 최대 = 상한 + 호출 1회분. 도구를 껐는데도 도구를 요청하면 INVALID_OUTPUT. 실패해도 쓴 토큰은 모두 합쳐 보고(예산 기록).
- **회원은 연결 헤더로만** — open(userId)에서 정해지고 모델은 도구 인자로 바꿀 수 없다(get_my_coupons 인자 없음). 도구는 전부 조회 전용.
  MCP_SERVICE_TOKEN이 비면 open이 LLM_UNAVAILABLE(+ERROR 로그). 연결 실패도 LLM_UNAVAILABLE(모델 호출 0). 도구 호출 실패는 예외가 아니라 isError 결과 → 모델이 설명.
- 질문마다 MCP 연결을 새로 연다(헤더에 회원이 실려 회원별 연결이 필요, 수십 ms라 무시할 만함).
- **FakeChatbotModel**(기본): 발급 말("발급해", "받아줘" …) → 발급 말을 뺀 검색 + "발급은 직접" 안내 / 내 쿠폰 말 → get_my_coupons / "3번 이벤트" → get_event /
  인사 → 도구 없이 안내 / 그 외 → search_events(문장 그대로). 도구 1회 → 결과를 틀에 넣은 답. 지난 대화는 쓰지 않는다. 토큰은 글자 수로 흉내.
- **AnthropicChatbotModel**: tools = mcp-server tools/list의 JSON Schema 그대로(properties·required·additionalProperties), 응답은 `Message.toParam()`으로
  **그대로** 이어 붙임(thinking 블록 보존 — append-only), 도구 결과는 한 user 메시지에 모두, 도구를 끌 땐 tool_choice none(정의는 유지),
  thinking 미전송(적응형), effort = app.llm.anthropic.effort(low), max_tokens 8000, fallbacks "default". refusal → CONTENT_REFUSED, max_tokens → INVALID_OUTPUT.
  재시도·서킷 브레이커는 AnthropicLlmClient와 공유(createMessage → guarded). 시스템 프롬프트 끝에 오늘 날짜·요일. **실제 API로는 아직 미확인**.
- 설정 app.llm.chatbot.{concurrency, max-tool-calls, turn-token-cap, max-tokens, deadline-margin, mcp.{url(MCP_SERVER_URL), service-token(MCP_SERVICE_TOKEN), request-timeout 25s}}.
- 제한: 컨테이너(llm 프로필)로 띄운 llm-service는 mcp-server(호스트 127.0.0.1)에 닿지 않는다 → 챗봇은 호스트 bootRun에서만. mcp-server 컨테이너화는 나중.

### MCP 서버 (7단계 B1·B2) — backend/mcp-server, com.couponrush.mcpserver, 포트 8082
```
MCP 클라이언트 ─ POST/GET /mcp (Streamable HTTP, 세션 헤더 Mcp-Session-Id) ─▶ HttpServletStreamableServerTransportProvider
  └▶ McpSyncServer (serverInfo coupon-rush, instructions: 조회 전용·발급은 url 안내)
       ├ search_events {query 1~200자} → POST /api/coupons/search → PENDING이면 0.5초마다 GET .../{id} (최대 20초) → COMPLETED/FALLBACK
       ├ get_event {eventId ≥ 1}       → GET /api/coupons/{id}
       └ get_my_coupons {} (B2, 로그인) → 사람: GET /api/users/me/coupons (Bearer 그대로) / 서버: GET /internal/users/{id}/coupons
```
- **SDK: io.modelcontextprotocol.sdk:mcp-core + mcp-json-jackson2 2.1.0** (Spring AI 없음). 2.2.0은 reactor-core 3.9.0-RC1 의존이라 Boot 3.5(reactor 3.7)와 안 맞음.
  전송이 HttpServlet이라 ServletRegistrationBean(asyncSupported)으로 등록. API는 jar를 javap로 확인함 — Tool.builder()(인자 없음)·securityValidator는
  deprecated → Tool.builder(name, jsonMapper, schemaJson), httpHeaderValidator 사용. MCP 프로토콜 매퍼는 Spring ObjectMapper와 분리(new ObjectMapper()).
- 다른 모듈에 의존하지 않는다 (coupon-api와는 공개 REST만, 응답 DTO는 필요한 필드만 + ignoreUnknown).
- 계층 (2026-11-21 분리): tool/EventTools(MCP 입구 = 컨트롤러: 도구 스키마·설명, 인자 검사, 결과·오류를 CallToolResult로) →
  event/EventQueryService(서비스: coupon-api 조회 + 결과 가공, MCP를 모름) → couponapi/CouponApiClient(바깥 REST 호출). 결과 모양은 event/EventViews.
- 도구 결과: 본문은 JSON 문자열. 이벤트마다 statusLabel(한국어), 기간은 "yyyy-MM-dd HH:mm KST", **url = 프론트 이벤트 페이지(발급은 사람이 여기서)**.
  search_events는 searchMethod AI_FILTER(조건 conditions) / KEYWORD(keywords + fallbackReason)를 알려 준다. 도구 주석 readOnly·idempotent·openWorld=false.
- 실패는 예외가 아니라 isError=true + 한국어 설명 (모델이 읽고 인자를 고치거나 사용자에게 설명). 404 → "찾을 수 없습니다: {coupon-api 메시지}",
  400 → 입력 오류, 5xx·연결 실패·검색 대기 초과 → 잠시 후 다시. coupon-api 타임아웃 연결 2초·응답 5초. 인자 검사는 핸들러도 직접 한다(빈 질문·0 이하 ID → coupon-api 호출 없음).
- 보안: server.address=127.0.0.1(루프백만 — 공개 도구는 인증 없이 쓰므로), Host 허용 목록 app.mcp.allowed-hosts(DNS 리바인딩 방지, 위반 421).
  컨테이너로 띄울 때(C2)는 compose 내부 네트워크만, 호스트 포트는 열지 않는다.
- **인증 (B2)** — auth/Caller(Anonymous / User(accessToken) / Service(userId)), auth/CallerAuthentication:
  **누구인지는 HTTP 헤더로만, 도구 인자로는 절대 안 받는다** (get_my_coupons 스키마는 properties {} + additionalProperties false — 프롬프트 인젝션으로 남의 쿠폰 조회 방지).
  | 헤더 | Caller | get_my_coupons |
  |---|---|---|
  | Authorization: Bearer {access} | User | GET /api/users/me/coupons에 같은 Bearer (mcp-server는 검증 안 함, 만료·블랙리스트는 coupon-api) |
  | X-Mcp-Service-Token(=MCP_SERVICE_TOKEN) + X-Mcp-User-Id | Service | GET /internal/users/{id}/coupons + X-Internal-Token(=INTERNAL_API_TOKEN) |
  | 없음 / 서비스 토큰만 | Anonymous / Service(null) | 호출 없이 "로그인이 필요합니다" 도구 오류 |
  거절(HTTP, initialize부터): 틀린 서비스 토큰·사용자 ID만 → 401, 서비스 토큰 + Bearer 함께·잘못된 사용자 ID·중복 헤더 → 400.
  SDK 연결: httpHeaderValidator(Host → 인증 순서) + contextExtractor(**HTTP 요청마다** Caller → exchange.transportContext()) — 세션 단위가 아니라 토큰을 바꾸면 다음 호출부터 반영.
  coupon-api 401: 사람 경로는 UNAUTHENTICATED("로그인이 필요합니다: {사유}" + 토큰 넣는 법), 내부 경로는 설정 오류라 UNAVAILABLE + ERROR 로그(두 서비스 INTERNAL_API_TOKEN 불일치).
  검색도 사람의 Bearer를 함께 보내 한도를 회원별로 (공개 경로라 만료 토큰이면 비로그인 처리). 서비스 호출(챗봇)의 검색은 coupon-api 내부 API
  POST /internal/users/{id}/coupons/search + X-Internal-Token으로 보내 **그 회원의 한도**로 센다(C2 — 공개 경로면 모든 챗봇 검색이 127.0.0.1 하나의 비로그인 한도를 나눠 씀).
  결과 폴링은 공개 GET /api/coupons/search/{id} 그대로.
  토큰 두 개를 나눈 이유: 구간마다 필요한 것만 가진다(llm-service는 coupon-api 내부 토큰을 모름, coupon-api는 MCP 토큰을 모름).
  mcp-server도 infra/.env를 읽는다(spring.config.import, 작업 폴더 backend/mcp-server).
- 테스트 McpToolsTest: 공식 SDK 클라이언트(HttpClientStreamableHttpTransport)로 실제 연결, coupon-api는 WireMock. 포트 고정 18082
  (Host 허용 목록을 기동 전에 정해야 해서 임의 포트 불가). 도구 목록·주석, PENDING 기다림(시나리오), FALLBACK, 대기 초과, 빈 인자, 404 메시지, 500, 연결 끊김 후 세션 유지.
  Host 위조(421)는 Java HttpClient가 Host 헤더를 못 바꿔 자동 테스트 없음 — curl로 수동 확인.

### LLM 다음 기능 계획 (7단계 이후 기능, 2026-11-09 수립 — 미구현)
```
[Claude Code 등 외부 MCP 클라이언트] ─┐
[llm-service ChatbotOrchestrator] ───────┴→ [mcp-server] ─REST→ [coupon-api] → DB
[프론트] ─HTTP→ [coupon-api] ─Kafka 요청/응답→ [llm-service]
```
- 자연어 검색: LLM은 질문 → 검색 필터 추출만, 실제 조회는 DB. 없는 이벤트를 지어낼 수 없고 결과 검증이 쉽다.
  검색은 AI 없이도 동작해야 하므로 LLM 실패 시 키워드 검색 fallback.
- MCP 서버는 별도 서비스(mcp-server). 처음엔 coupon-api 안 /mcp를 생각했으나 MSA 관점에서 변경 —
  선착순 발급을 처리하는 핵심 서비스에 AI용 프로토콜·트래픽·보안 노출면을 넣지 않는다(llm-service 격리와 같은 원칙).
  MCP는 도메인 로직 없는 프로토콜 어댑터(API Gateway/BFF 역할). 서비스가 늘어나도 창구 하나로 묶는다.
  llm-service에 두지 않는 이유: 도구를 "쓰는" 쪽이고 공개 포트가 없어 외부 클라이언트가 붙을 수 없다.
- 도구는 전부 조회 전용. 챗봇이 "발급해줘"를 받으면 이벤트 페이지 링크로 안내만 (발급 경로 LLM 금지 규칙).
- 오케스트레이터 = llm-service 안 단일 에이전트 tool use 루프. 멀티 에이전트(라우터 + 전문 에이전트)는 도구 3개 규모에선
  복잡도·비용만 늘어 제외, 도구가 늘면 재검토.
- fake 제공자로도 오케스트레이터 전체 흐름(Kafka·MCP·DB 조회·제한)은 실제로 동작한다. fake는 LLM 판단만 규칙으로 대신
  (키워드 → 도구 호출, 도구 결과 → 템플릿 답변). 도구 선택 품질·답변 자연스러움은 실제 키로 마지막에 확인.
- 벡터 검색(pgvector 임베딩)은 보류: Anthropic에 임베딩 API가 없어 별도 제공자(Voyage 등)나 로컬 모델이 필요하고,
  이벤트 수가 적은 지금은 필터 추출로 충분. 의미 검색("커피 할인" → "아메리카노 쿠폰")이 필요해지면
  필터로 좁히고 벡터 유사도로 정렬하는 하이브리드로 추가.

### WebSocket 채팅 계획 (D, 2026-11-18 수립 — 미구현)
```
[브라우저] ① POST /api/web-chat/tickets (로그인) ─▶ coupon-api: 1회용 티켓(30초) Redis 저장
           ② ws /ws/web-chat + STOMP CONNECT(ticket) ─▶ web-chat-service: 티켓 확인·삭제 → 사용자 확정
           ③ SUBSCRIBE /topic/events/{id}, SEND /app/events/{id}/chat
                web-chat-service ─ Redis pub/sub ─ web-chat-service(다른 인스턴스)
```
- **별도 서비스(web-chat-service)**: WebSocket은 연결을 오래 쥔다 → 선착순 오픈 순간 coupon-api의 스레드·메모리를 쓰지 않게 분리 (llm-service·mcp-server와 같은 원칙).
- **티켓 인증**: 브라우저 WebSocket은 Authorization 헤더를 못 붙이고, URL에 토큰을 싣지 않는 규칙 → 짧은 1회용 티켓을 첫 STOMP 프레임으로.
  티켓은 coupon-api가 발급하므로 web-chat-service는 JWT 비밀키를 모른다 (비밀값을 여러 서비스에 나누지 않음).
- **브로커**: 서버 안은 STOMP 단순 브로커, 서버 간은 Redis pub/sub을 직접 연결 — RabbitMQ 릴레이를 쓰면 scale-out 문제가 숨겨지므로 학습 목적상 직접 다룬다.
- 보호: 메시지 크기 상한, 사용자별 도배 제한, 느린 클라이언트(전송 버퍼·시간 제한), 보낸 사람은 서버가 채움. 발급 기능과 무관(조회·대화만).
- 챗봇(C)과 구분: 챗봇은 서버 → 브라우저 한 방향 푸시라 SSE가 더 단순하다. WebSocket은 사람끼리 양방향 대화에 쓴다.

### 대화 저장 (7단계 D6) — web-chat-service room/(WebChatHistoryWriter·WebChatMessageRepository·WebChatHistoryService·WebChatHistoryController), 데이터베이스 web_chat
```
SEND → WebChatService: 도배·본문 검사 → 방에 발행 → WebChatHistoryWriter.enqueue (메모리 대기열, 기다리지 않음)
                                  전용 스레드(web-chat-history-writer) 0.2초마다 → 최대 500개 묶음 INSERT ... ON CONFLICT (message_id) DO NOTHING
GET /api/web-chat/rooms/{eventId}/messages?beforeId=&size= → WHERE event_id = ? [AND id < ?] ORDER BY id DESC LIMIT size+1 → 오래된 것부터 + nextBeforeId
```
- **원칙: 서비스마다 자기 데이터(database per service)**. 나누는 수준은 "같은 서버, 데이터베이스를 따로"
  (DB 서버를 따로 두지 않음 — 비용·로컬 환경 단순. 서버 분리는 트래픽·장애 격리가 필요할 때).
  핵심은 물리 분리가 아니라 **다른 서비스 데이터를 직접 읽지 않고 API·이벤트로만** 받는 것.
- 구성: 같은 Postgres 컨테이너에 데이터베이스 `web_chat`(이름 고정) + 전용 계정(WEB_CHAT_DB_USER, 기본 web_chat, 슈퍼유저 아님, web_chat의 주인).
  web-chat-service DataSource = jdbc:postgresql://POSTGRES_HOST:POSTGRES_PORT/web_chat, 자기 Flyway(web-chat-service/src/main/resources/db/migration, V1부터).
  **coupon_rush 의 PUBLIC CONNECT를 거뒀다** → web_chat 계정은 접속부터 거절("User does not have CONNECT privilege"). coupon-api 계정(슈퍼유저)은 영향 없음.
  ⚠ 9단계(Spring Batch) coupon_batch 계정은 coupon_rush 에 `GRANT CONNECT`가 따로 필요하다.
- 초기화: infra/postgres/20-web-chat-db.sh (docker-entrypoint-initdb.d, 빈 볼륨 첫 생성 때만 자동). psql 변수 + `\gexec`로 "없으면 만들기" → 여러 번 실행해도 됨,
  단 이미 있는 계정의 비밀번호는 바꾸지 않는다. **기존 볼륨**: `docker compose up -d`(env·마운트 반영해 컨테이너 재생성, 볼륨 유지) 후
  `docker exec coupon-rush-postgres sh /docker-entrypoint-initdb.d/20-web-chat-db.sh`. .sh 는 .gitattributes 로 LF 고정(CRLF면 컨테이너 sh 가 실패).
  WEB_CHAT_DB_PASSWORD 는 compose 에서 필수(`:?`) — 없으면 `docker compose up` 자체가 멈춘다(빠뜨리면 바로 알게).
- 테이블 web_chat_messages(id IDENTITY, message_id UUID UNIQUE, event_id, sender_id, sender_nickname VARCHAR(50), content VARCHAR(500), sent_at TIMESTAMPTZ), 인덱스 (event_id, id).
  회원·이벤트 FK 없음 — users·coupons는 coupon-api 데이터베이스라 참조 불가 → **보낼 때의 닉네임을 복사**. VARCHAR(500)은 PostgreSQL이 글자(코드 포인트)로 세서 CONTENT_MAX와 같다.
  JPA 없이 JdbcTemplate (묶음 INSERT·커서 조회 두 가지뿐).
- **저장 때문에 대화가 느려지거나 멈추지 않게**: 방에 먼저 발행, 저장은 대기열에 넣기만. DB 실패 → 그 묶음을 들고 retry-delay(1s, 2배씩) 뒤 재시도, 그동안 뒤 묶음이 앞지르지 않음,
  max-attempts(5)를 넘기면 버리고 ERROR(저장할 수 없는 묶음 하나가 영원히 막지 않게). 대기열(10,000)이 차면 저장만 포기(WARN, 1·1000건마다).
  INSERT 는 트랜잭션 없이 → 중간 실패 후 재시도해도 ON CONFLICT로 중복 없음. 지표: savedCount / failedAttemptCount / droppedCount.
  전용 스레드: @Scheduled 스레드(= 브로커 하트비트 스케줄러)를 쓰면 DB가 느릴 때 하트비트까지 밀린다. SmartLifecycle phase MAX-1000 = WebSocket·브로커보다 늦게 멈추며 남은 것 1회 저장.
  Hikari connection-timeout 3000(ms 숫자 — "3s"는 바인딩 실패로 기동 실패했음), pool 4.
- 한계: 메모리 대기열이라 서버가 갑자기 죽으면 저장 전 메시지(≤ 0.2초 + 재시도 중 묶음)를 잃는다 — 채팅은 잃어도 되는 데이터로 보고 단순함을 택함
  (잃으면 안 되면 Kafka 같은 내구성 큐 경유). 저장 순서 id ≈ 보낸 순서(서버 1대 기준, D3 여러 대면 서버 간 몇 백 ms 엇갈릴 수 있음).
- 시각: WebChatService가 sentAt을 **마이크로초로 자른다**(PostgreSQL 정밀도) — 실시간으로 받은 값과 지난 대화의 값이 같게.
- 조회: 공개(방 구독과 같음), size 기본 50·최대 100, beforeId/size/이벤트 ID 오류는 400 WEB_CHAT_INVALID_REQUEST, DB 장애는 503 WEB_CHAT_HISTORY_UNAVAILABLE(대화는 계속).
  OFFSET이 아니라 id 커서 — 새 메시지가 와도 페이지가 밀리지 않고 뒤 페이지도 인덱스로 바로. 저장이 비동기라 방금 메시지는 아직 없을 수 있음 → 화면은 구독 먼저, 기록 조회 후 messageId로 합침(D4).
  경로 /api/web-chat/rooms/** 는 web-chat-service, /api/web-chat/tickets 는 coupon-api — 프록시가 경로로 나눈다(D4 Vite, 배포 땐 게이트웨이).
- 아직 없음: 보관 기간·정리(채팅은 개인정보 — 예: 이벤트 종료 후 30일, Spring Batch 연습 후보), 메시지 삭제·신고, 조회 횟수 제한.
- 테스트: @WebChatIntegrationTest(메타 애너테이션 — 설정을 같게 해 컨텍스트·컨테이너 공유, 저장 간격 50ms·재시도 200ms·page-size 3),
  Testcontainers Postgres(pgvector 이미지, 데이터베이스 web_chat) + Redis. DB 장애는 `ALTER TABLE web_chat_messages RENAME TO web_chat_messages_broken`( 복구).
  비동기 저장은 Awaitility로 조회 API를 기다림, 테스트마다 새 이벤트 ID(늦게 저장된 앞 테스트 메시지와 안 섞이게).

### Spring Batch 계획 (E, 2026-11-22 결정 — 미구현)
- **기능: 종료된 이벤트 결과 집계** — 끝난 이벤트마다 발급 이력을 읽어 통계 테이블에 저장
  (총 발급 수, 오픈 → 소진까지 걸린 시간, 분 단위 발급 추이, 실패 사유별 건수). 관리자 화면 그래프로 표시(선택).
  배울 것: chunk(묶음 읽기·쓰기), JobParameters(날짜)로 중복 실행 방지, 실패 지점부터 재시작, 다시 돌려도 같은 결과(멱등), 실행 이력.
  대조 점검(Redis ↔ DB)을 첫 Step으로 넣을지는 세부 설계 때 (넣으면 Redis 키 구조를 몰라도 되게 coupon-api 대조 API 호출 고려).
- **위치: 별도 모듈 coupon-batch** (현업에서 더 흔한 구조 — api / batch 분리 배포). 이유: 오픈 순간 발급 서버 자원을 나눠 쓰지 않음,
  API 서버가 여러 대여도 배치는 한 번만, 따로 배포, "실행 → Job → 종료" 프로세스로 실행 이력·재시작이 깔끔.
- **DB는 coupon-api와 같은 coupon_rush (현업 표준 — 같은 도메인의 API·배치는 DB 공유)**. 배치는 그 서비스 데이터를 읽어 가공하는 일이라
  DB를 나누면 쿠폰 데이터를 다시 복사해야 한다. DB를 따로 두는 건 규모가 커진 뒤의 선택(읽기 전용 복제본에서 읽기, 분석용 DB·웨어하우스 적재).
  대신 같은 DB 안에서 **스키마와 계정으로 영역·권한을 나눈다**:
  ```
  PostgreSQL 서버 하나
  ├─ 데이터베이스 coupon_rush
  │   ├─ 스키마 public ← 주인 coupon-api (업무 테이블 + 통계 테이블, coupon-api Flyway)
  │   │      coupon-batch: 발급 관련 테이블 읽기만, 통계 테이블 쓰기
  │   └─ 스키마 batch  ← 주인 coupon-batch (Spring Batch BATCH_* 실행 이력, coupon-batch 자기 Flyway·별도 이력 테이블)
  └─ 데이터베이스 web_chat ← 주인 web-chat-service (D6)
  ```
  - 통계 테이블은 public / coupon-api 소유: 관리자 화면에서 coupon-api가 보여 줄 데이터라서. 배치는 쓰기만.
  - BATCH_* 는 batch 스키마 / coupon-batch 소유: 쿠폰 업무와 무관한 프레임워크 기록이라 업무 테이블과 섞지 않는다
    (Flyway 두 개가 같은 DB를 다루되 스키마·이력 테이블이 달라 충돌하지 않음. spring.batch.jdbc.table-prefix = batch.BATCH_).
  - **배치 전용 DB 계정 coupon_batch**: public 발급 관련 테이블 SELECT만, 통계 테이블 INSERT/UPDATE/DELETE, batch 스키마 전부.
    배치 버그가 발급 데이터를 바꾸는 사고를 문서 규칙이 아니라 DB 권한으로 막는다. 계정·권한은 Postgres 초기화 스크립트 + 기존 볼륨용 수동 절차,
    새 env(배치 계정 비밀번호) → infra/.env.example·README 환경변수 표 갱신.
  - 엔티티 공유 없이 SQL(JdbcCursorItemReader 등)로 접근. llm-service·mcp-server·web-chat-service는 여전히 coupon_rush 에 접근하지 않는다.
  - E 시작 때 공통 메모의 DB 규칙을 이 내용으로 바꾼다 ("coupon_rush 의 주인은 쿠폰 도메인 = coupon-api + coupon-batch, 영역은 스키마·계정으로").
  - 비교: **같은 서비스(쿠폰 API·배치)는 DB 공유 + 스키마·권한 구분 / 다른 서비스(채팅, D6)는 데이터베이스 자체를 분리.**
- 같은 DB를 쓰는 위험과 대비: 스키마 변경에 깨짐(배치 테스트도 coupon-api 마이그레이션을 적용한 DB에서) / 발급 성능 방해(한가한 시간 실행, 작은 chunk·커넥션 풀) /
  쓰기 충돌(발급 테이블은 계정 권한으로 쓰기 불가) / 규칙 중복(집계에 필요한 규칙만 SQL, 많아지면 coupon-domain 모듈 분리).

### 채팅 서버 (7단계 D1) — backend/web-chat-service, com.couponrush.webchat, 포트 8083
```
ws /ws/web-chat (STOMP, SockJS 없음) ─▶ StompInboundGuard(입구 검사) ─┬ /app/events/{id}/chat → WebChatController → WebChatService → WebChatMessagePublisher → /topic/events/{id}
                                                                      └ SUBSCRIBE → 단순 브로커
```
| 주소 | 누가 | 내용 |
|---|---|---|
| SUBSCRIBE `/topic/events/{id}` | 누구나 (이벤트 페이지가 공개) | `WebChatMessage(messageId, eventId, senderId, senderNickname, content, sentAt)` |
| SEND `/app/events/{id}/chat` | 로그인 연결만 | 바디 `{"content"}`만 읽음 — senderId 등 다른 필드는 무시(보낸 사람·시각·ID는 서버가 채움) |
| SUBSCRIBE `/user/queue/errors` | 로그인 연결 | 내 메시지 거절 `{"code","message"}` (보낸 세션에만, broadcast=false) |
- 패키지: auth/(WebChatUser = Principal, WebChatTicketAuthenticator(D2), StompInboundGuard), room/(WebChatController·WebChatService·메시지·도배 제한·발행, D6 저장·지난 대화 WebChatHistory*·WebChatMessageRepository), config/.
  (지난 대화를 room/ 에 둔 이유: 서비스가 저장 대기열을, 저장이 메시지를 서로 써서 패키지를 나누면 순환 의존)
  계층: WebChatController = STOMP 입구(로그인 연결 확인 + 거절을 /user/queue/errors로), WebChatService = 도배 제한 → 본문 규칙(공백·500자) → 메시지 만들기 → 발행
  (STOMP를 몰라 연결 없이 단위 테스트 — WebChatServiceTest). WebChatSendRequest는 값만(검사는 서비스).
- 보내기 주소 `/app/events/{id}/chat`의 chat은 그대로 둠 — /ws/web-chat 안에서만 쓰는 동작 이름이라 챗봇과 섞이지 않는다.
- **거절 두 종류**: 정상 화면이 보내지 않는 요청(허용 목록 밖 주소 구독·보내기, /topic 직접 SEND, 비로그인 SEND)은 ERROR 프레임(message 헤더 = 코드) + 연결 종료 /
  메시지 내용(WEB_CHAT_EMPTY_MESSAGE·WEB_CHAT_MESSAGE_TOO_LONG·WEB_CHAT_INVALID_MESSAGE)·도배(WEB_CHAT_RATE_LIMITED)는 /user/queue/errors, 연결 유지. 코드 접두사 WEB_CHAT_(챗봇 CHATBOT_*와 구분).
- **/topic 직접 SEND 금지가 핵심**: 단순 브로커는 클라이언트가 /topic에 보낸 SEND를 그대로 뿌려서, 막지 않으면 컨트롤러(보낸 사람 채우기·검사)를 건너뛴 사칭이 된다.
  주소는 WebChatDestinations의 정규식(이벤트 ID 1~18자리)만.
- **순서**: preserveReceiveOrder(같은 연결의 프레임 순서대로 처리) + preservePublishOrder(한 연결로 가는 메시지 순서 유지). 연결끼리는 병렬.
  ⚠ preserveReceiveOrder에서는 Spring(OrderedMessageChannelDecorator)이 **인터셉터 예외를 로그만 남기고 삼킨다** → ERROR 프레임이 안 나감.
  그래서 입구 검사는 예외를 던지지 않고 clientOutboundChannel로 ERROR 프레임을 직접 보낸 뒤 null(프레임 버림)을 반환한다 (Spring이 ERROR를 보낸 뒤 연결을 닫음).
  ⚠ preservePublishOrder에서 **세션이 여러 개인 회원에게 convertAndSendToUser**(broadcast)를 하면 "Expected mutable SimpMessageHeaderAccessor"로 실패한다
  (Spring 6.2.19에서 확인). 지금은 broadcast=false만 써서 해당 없음 — 회원의 모든 탭에 보내야 하면 세션별로 보내거나 이 설정을 다시 볼 것.
- 도배 제한(WebChatRateLimiter): 회원 기준(탭 합산), 최근 10초 5개(app.web-chat.rate-limit.*), 한도로 거절된 시도는 세지 않음, 내용 검사보다 먼저 셈(잘못된 메시지 연타도 한도 안).
  형식 오류(JSON 아님)는 컨트롤러 인자 변환에서 나서 세지 않는다. D3부터 Redis(아래 "서버 간 전달 (D3)"의 도배 제한) — D1의 메모리 장부·정리 스케줄러(@Scheduled, @EnableScheduling)는 없앰.
- 보호: Origin 허용 목록(app.web-chat.allowed-origins, 위반 403 — Origin 없는 비브라우저 클라이언트는 대상 아님), 프레임 8KB(넘으면 연결 종료),
  보낼 버퍼 512KB·10초(느린 클라이언트 끊기), 하트비트 10초(브로커 스케줄러는 messageBrokerTaskScheduler를 @Lazy로 받음).
- 이벤트가 실제로 있는지는 확인하지 않는다(이벤트는 coupon-api 데이터) — 없는 이벤트 방은 빈 방일 뿐. 필요하면 D2 티켓 발급 때 coupon-api가 확인.
- 인증: D1은 전원 비로그인(헤더로 회원 ID를 받는 개발용 인증은 두지 않음 — 켜진 채 배포되면 사칭) → D2 티켓 (아래 메모).
  WebChatMessagePublisher는 전달 방식의 경계 — D1은 이 서버 브로커로 바로, D3부터 RedisWebChatMessagePublisher(아래 "서버 간 전달 (D3)").
- 테스트 WebChatRoomTest: WebSocketStompClient로 실제 연결, 회원은 실제 티켓(테스트가 Redis(Testcontainers)에 넣음)으로. 방 구독 확인은 서버 SimpMessagingTemplate로
  표시용 메시지(probe)를 받을 때까지, 오류 큐 구독 확인은 같은 연결로 형식 오류 하나를 보내 그 거절을 받는 것으로(순서 보장 덕분).
  "안 받음"은 거절 응답을 받은 시점에 확인(거절은 브로커 전에 끝남) — sleep은 "다른 탭에 안 감" 300ms 하나뿐. 테스트마다 새 회원 ID(도배 상태가 컨텍스트에 남음).

### 채팅 연결 티켓 (7단계 D2) — coupon-api com.couponrush.webchat + web-chat-service auth/ + libs/web-chat-contract
```
브라우저 ① POST /api/web-chat/tickets (로그인, coupon-api) → 200 {ticket, expiresAt}, Cache-Control: no-store
           coupon-api: 무작위 32바이트(SecureRandom, base64url 43자) → SET NX web-chat:ticket:{SHA-256(티켓)} = WebChatTicket JSON, EX 30s
        ② ws /ws/web-chat + STOMP CONNECT (ticket 헤더) → web-chat-service: GETDEL → 있으면 WebChatUser(userId, nickname)로 연결
```
| Redis 키 | 쓰는 쪽 | 내용 |
|---|---|---|
| `web-chat:ticket:{sha256}` | coupon-api 생성 / web-chat-service 꺼내며 삭제 | `{userId, nickname, issuedAt}`, TTL 30초 |
- **왜 티켓인가**: 브라우저 WebSocket은 Authorization 헤더를 못 붙이고, URL에 토큰을 싣지 않는 규칙. 로그인 확인은 JWT를 아는 coupon-api가 하고,
  web-chat-service는 JWT 비밀키도 users 테이블도 모른다(비밀값·데이터를 나누지 않음). 보낸 사람·닉네임은 티켓에 담겨 사칭 불가, 닉네임은 발급 시점 값.
- **1회용 = GETDEL**(꺼내면서 지우기, 원자적): GET 후 DEL로 나누면 같은 티켓 동시 연결이 둘 다 통과할 수 있다 — 동시 8연결 중 1개만 성공을 테스트로 고정.
- **키는 원문이 아니라 SHA-256 해시**(refresh token과 같은 방식): Redis 내용이 새도 쓸 수 있는 티켓이 없다. 짧은 TTL(30초)도 같은 이유.
- 형식(base64url 43자)이 아니면 Redis를 보기 전에 거절(긴 헤더로 해시 계산을 시키지 못하게). 키 규칙·값 모양은 계약 모듈 하나(WebChatTickets·WebChatTicket) —
  키 규칙은 밖에서 계산한 해시 값으로 고정(WebChatTicketsTest), 값은 필드 추가만(ignoreUnknown).
- 잘못된 티켓을 비로그인으로 낮춰 받지 않는다 — 연결을 거절해(WEB_CHAT_UNAUTHORIZED) 화면이 새 티켓으로 다시 연결하게(D4). 재연결도 매번 새 티켓.
  티켓 내용을 못 읽으면(계약 불일치) ERROR 로그 + 같은 거절. Redis 장애 = 확인할 수 없으니 WEB_CHAT_ERROR로 연결 종료.
- coupon-api: 회원이 없으면 404 USER_NOT_FOUND, Redis 저장 실패는 503 WEB_CHAT_TICKET_UNAVAILABLE. /api/web-chat/** 는 기본 규칙(로그인 필요)이라 SecurityConfig에 따로 없다.
  구조 규칙(ArchitectureTest): webchat → coupon·llm·search·chatbot 금지, web-chat-contract는 webchat 패키지만.
- 한계: 티켓 발급 횟수 제한 없음(로그인 회원만, 30초 뒤 사라짐 — 남용이 보이면 회원별 분당 한도). 티켓이 이벤트(방)에 묶이지 않음(연결 하나로 여러 방 구독 가능).

### 서버 간 전달 (7단계 D3) — web-chat-service room/(RedisWebChatMessagePublisher·WebChatRelayListener·WebChatRelay), config/WebChatRelayConfig
```
SEND (서버 A) → WebChatService: 도배·본문 → RedisWebChatMessagePublisher: PUBLISH web-chat:messages (JSON WebChatMessage) → 성공하면 저장 대기열(A만)
Redis ─▶ 모든 서버(A 포함)의 WebChatRelayListener (단일 스레드) → 자기 단순 브로커 /topic/events/{eventId}
```
- **보낸 서버도 자기 브로커에 직접 넣지 않는다**: Redis가 한 채널을 모든 구독자에게 같은 순서로 주므로 어느 서버에서 보든 대화 순서가 같다.
  직접 넣고 남에게만 보내면 서버마다 "자기 메시지가 먼저"가 되어 화면마다 순서가 다르다. 대가: 왕복 1번, Redis가 죽으면 같은 서버 안의 대화도 멈춤
  (연결 티켓도 Redis라 이미 같은 의존).
- **채널 하나에 모든 방**(본문의 eventId로 방을 고름). 방마다 채널 + 구독자 있는 방만 SUBSCRIBE는 서버가 안 보는 방을 받지 않지만 방별 인원 계산이 필요 —
  서버 몇 대 규모에선 불필요, 받는 양이 문제가 되면 그때. 채널 이름은 이 서비스만 써서 계약 모듈에 두지 않음.
- **리스너 단일 스레드**: RedisMessageListenerContainer 기본 실행기(SimpleAsyncTaskExecutor)는 메시지마다 새 스레드 → 순서가 뒤바뀔 수 있다(spring-data-redis 3.5.13 바이트코드로 확인).
  실행기는 Bean으로 등록하지 않는다(Executor Bean이 생기면 Boot 기본 applicationTaskExecutor가 물러남). 리스너에는 브로커 넘기기만 — 저장 같은 느린 일 금지.
- **저장은 보낸 서버만 한 번** — 받는 서버까지 저장하면 서버 수만큼 INSERT(행은 message_id UNIQUE가 막아도 쓰기는 N배). 발행 실패면 저장하지 않음(아무도 못 본 메시지가 기록에만 남지 않게).
- 실패: 발행(Redis) 실패 → WEB_CHAT_UNAVAILABLE을 /user/queue/errors로(연결 유지, 다시 보내면 됨). 읽을 수 없는 전달 메시지(형식·필수 값 없음·다른 버전) → WARN 후 버림.
  JSON은 Boot ObjectMapper(모르는 필드 무시) → 배포 중 필드 추가는 호환, 필드 삭제·이름 변경은 받는 쪽이 버린다.
- 한계: pub/sub은 저장하지 않는다(at-most-once) — 구독 연결이 끊긴 동안 그 서버 접속자는 메시지를 못 받는다(컨테이너가 다시 구독, 지난 대화에는 있음 → D4 재연결 때 기록 다시 조회).
  서버 간 순서는 Redis 기준이고 sentAt(각 서버 시계)과 몇 ms 어긋날 수 있다.
- **도배 제한도 Redis** (서버 메모리로 세면 서버마다 장부가 따로라 ① 동시에 켜진 대수만큼 한도가 늘고 ② 다른 서버로 다시 연결하면 0부터 — active-active라서 생기는 문제):
  `web-chat:rate:{userId}` ZSET(score = 보낸 시각 ms, member = 시도마다 UUID — 같은 ms 두 번도 따로), resources/redis/web-chat-rate-limit.lua 하나로
  ZREMRANGEBYSCORE(창 밖 정리) → ZCARD ≥ 한도면 0(세지 않음) → ZADD + PEXPIRE(창) → 1. 창 (now - 10s, now], 키는 창 길이 뒤 사라져 정리 작업 없음.
  시각은 앱 Clock(ARGV) — 테스트에서 고정 가능, 서버 간 NTP 차이(ms)는 10초 창에 무시할 만함. Redis TIME을 쓰면 시계 하나지만 테스트로 시간을 못 움직인다.
  Redis 장애면 거절(WEB_CHAT_UNAVAILABLE) — 통과시켜도 이어지는 발행이 같은 Redis라 어차피 실패. 고정 창 INCR은 경계에서 두 배까지 허용돼서 기존 미끄러지는 창을 유지했다.
  현업 메모: 메시지 한도는 앞단(게이트웨이)이 못 센다(WebSocket 안 STOMP 프레임을 해석하지 않음 — 앞단은 IP별 연결 수·연결 속도). 큰 서비스는 연결별 메모리 제한(느슨하게, Redis 앞 방어선) + 회원별 Redis 제한 두 겹 — 여기는 회원별 Redis만.
- 테스트 WebChatScaleOutTest: A = @WebChatIntegrationTest 컨텍스트, B = SpringApplicationBuilder로 띄운 두 번째 앱(같은 컨테이너, RedisConnectionDetails·JdbcConnectionDetails에서 주소).
  **B 설정은 명령줄 인자로** — 기본 속성(properties())은 우선순위가 낮아 infra/.env 값에 덮인다. probe는 그 서버 브로커로만 가므로 서버별 구독 확인에 쓴다.
  B로 보낸 테스트는 저장 완료까지 기다리고 끝낸다(다음 테스트의 "B 저장 수 0" 검사가 흔들리지 않게).
- 로컬 두 대: `.\gradlew :web-chat-service:bootRun --args="--server.port=8084"`, 확인 `docker exec coupon-rush-redis redis-cli PUBSUB NUMSUB web-chat:messages` → 2.

## 자주 쓰는 명령 (PowerShell 기준)
- 프론트 실행: `cd frontend; npm install; npm run dev` → http://localhost:5173 (백엔드 bootRun 8080이 먼저 떠 있어야 함)
- 프론트 테스트 / 빌드: `npm test` / `npm run build` (tsc 타입 검사 + vite build)
- llm-service 실행(선택, 문구 생성용): 컨테이너 `cd infra; docker compose --profile llm up -d --build` (로그 `docker logs -f coupon-rush-llm-service`)
  또는 호스트 `cd backend; .\gradlew :llm-service:bootRun` → 8081 (fake 제공자). 둘 중 하나만
- MCP 서버 실행: `cd backend; .\gradlew :mcp-server:bootRun` → http://localhost:8082/mcp (coupon-api 8080이 떠 있어야 도구가 동작)
- 채팅 서버 실행: `cd backend; .\gradlew :web-chat-service:bootRun` → ws://localhost:8083/ws/web-chat (Redis·Postgres의 web_chat 데이터베이스 필요 — 처음 한 번 README 2-3의 20-web-chat-db.sh. 보내기는 coupon-api 티켓으로 연결한 회원만, 화면은 이벤트 상세의 채팅 — Vite 프록시 경유)
- 수동 확인 절차: docs/manual-check.md (로그인·발급·관리자·AI 문구·장애 격리·예산·모바일, 기록표 포함)
- 하루 LLM 사용량: `docker exec coupon-rush-redis redis-cli HGETALL "llm:usage:$((Get-Date).ToString('yyyyMMdd'))"`
- 전체 테스트: `cd backend; .\gradlew test` (모든 모듈). 한 모듈만: `.\gradlew :coupon-api:test`
- 동시성 테스트만: `.\gradlew :coupon-api:test --tests "com.couponrush.coupon.CouponIssueConcurrencyTest" --rerun`
  → 결과 숫자: `coupon-api\build\reports\tests\test\classes\com.couponrush.coupon.CouponIssueConcurrencyTest.html` 의 Standard output
- 방식별 성능 비교: `.\gradlew :coupon-api:benchmark` (@Tag("benchmark"), 기본 test에서 제외, 전체 약 12분)
  → `coupon-api\build\benchmark\coupon-issue-strategies.md` (재고 100/1000), `coupon-api\build\benchmark\coupon-issue-scale.md` (1k/10k/중복 요청),
    `coupon-api\build\benchmark\coupon-issue-async.md` (동기 vs 비동기 응답/완료 시간, 파티션·컨커런시 — 약 4분)
  하나만: `.\gradlew :coupon-api:benchmark --tests "com.couponrush.coupon.AsyncIssueBenchmarkTest"`
  ⚠ AsyncIssueBenchmarkTest는 테스트용 브로커의 토픽 파티션을 6으로 늘린다 (benchmark JVM 안에서만, 컨테이너는 실행마다 새로 뜸)
- 동기/비동기 바꾸기: `.env`에 `COUPON_ISSUE_MODE=sync` (기본 async) → bootRun 재시작
- Kafka 토픽/컨슈머 보기:
  `docker exec coupon-rush-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic coupon-issue-requests`
  `docker exec coupon-rush-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group coupon-issue-consumer`
  (CURRENT-OFFSET = 커밋된 오프셋, LAG = 아직 처리·커밋 안 된 메시지 수)
- DLT 메시지 보기:
  `docker exec coupon-rush-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic coupon-issue-requests.DLT --from-beginning --property print.headers=true --timeout-ms 5000`
- 실제 서버에서 방식 바꾸기(sync 모드일 때): `.env`에 `COUPON_ISSUE_STRATEGY=redis` 등 → bootRun 재시작
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
3. infra/.env.example → infra/.env 복사 후 값 채우기 (.env는 git에 없음, 변수 설명은 README.md "환경변수")
4. git config --global user.name / user.email 설정
5. 확인: cd infra && docker compose up -d → cd ../backend && ./gradlew test (모든 모듈)
   프론트: Node.js 20 이상 설치 → cd frontend && npm install && npm test
6. 기존 로컬 DB가 Flyway 도입 전 것이면 위 "DB / Flyway" 메모대로 정리
