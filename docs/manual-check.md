# 수동 확인 절차 (6·7단계 마무리)

자동 테스트(백엔드 483개, 프론트 132개 — 2026-11-21)로 확인하지 못하는 것만 사람이 직접 봅니다:
**실제 소셜 로그인, 실제 브라우저 화면·모바일 폭, 서비스 4개를 모두 띄운 상태의 연결.**
명령은 Windows PowerShell 기준이고, 소요 시간은 약 30~40분입니다 (실제 Claude 확인 제외).

결과는 맨 아래 [기록표](#기록표)에 체크하고, 문제가 있으면 "증상 / 화면 / 터미널 로그 한 줄"을 남겨 주세요.

---

## 0. 준비 (처음 한 번)

| 확인 | 방법 |
|---|---|
| Docker Desktop 실행 중 | 작업 표시줄 고래 아이콘 |
| `infra/.env` 필수값 | README "환경변수 > 필수" — 비어 있는 값 없음 |
| 최신 코드 | `git pull` |
| 관리자 계정 | 소셜 로그인 1회 후 README "4. 관리자 계정" 대로 `role='ADMIN'` |
| 브라우저 | Chrome (모바일 폭은 DevTools 기기 툴바 사용) |

`infra/.env`에 `LLM_PROVIDER`·`LLM_API_KEY`는 **넣지 않습니다** (fake 제공자로 확인 — 비용 0).

---

## 1. 띄우기 (터미널 3개)

```powershell
# 터미널 1 — 인프라 + llm-service 컨테이너
cd infra
docker compose --profile llm up -d --build
docker compose --profile llm ps            # 4개 모두 running (healthy) 가 될 때까지 (llm-service 는 40초쯤)
docker logs coupon-rush-llm-service | Select-String "LLM 제공자"   # → "LLM 제공자: fake"
```
> 7단계에서 Kafka 설정(리스너 추가, 데이터 위치를 볼륨으로)이 바뀌었습니다. 다른 PC에서는 첫 `up` 때 kafka 컨테이너가 다시 만들어지고
> **이전 토픽·메시지는 사라집니다** (그전까지 데이터가 볼륨이 아닌 컨테이너 안 /tmp 에 있었기 때문). 토픽은 coupon-api·llm-service 가 뜰 때 다시 만들어지니 그대로 진행하면 됩니다.
> 이후로는 컨테이너를 다시 만들어도 유지됩니다.

```powershell
# 터미널 2 — coupon-api (8080)
cd backend
.\gradlew :coupon-api:bootRun
# 새 터미널에서: curl.exe http://localhost:8080/actuator/health → {"status":"UP",...}
# 처음 뜰 때 Flyway 가 V5(llm_requests), V6(coupons.description)을 적용한다
```

```powershell
# 터미널 3 — 프론트 (5173)
cd frontend
npm install
npm run dev
```

브라우저: http://localhost:5173

---

## 2. 확인 항목

각 항목의 **기대 결과**와 다르면 기록표에 ✗ 와 증상을 적습니다.

### A. 로그인 (실제 제공자 — 자동 테스트 불가)

| # | 하는 일 | 기대 결과 |
|---|---|---|
| A1 | [로그인] → 카카오(쓰는 제공자 전부) | 제공자 화면 → 돌아와서 헤더에 닉네임, 원래 보던 화면으로 복귀 |
| A2 | 로그인 상태에서 **새로고침(F5)** | 로그인 유지 (로그인 화면으로 튕기지 않음) |
| A3 | 주소창에 토큰이 보이는지 | URL 에 `accessToken` 같은 값이 **없다** |
| A4 | DevTools > Application > Local/Session Storage | 토큰이 **없다** (세션 스토리지에는 돌아갈 경로만) |
| A5 | [로그아웃] → 새로고침 | 로그아웃 상태 유지 |

### B. 이벤트 목록·상세·발급

준비: 관리자 화면에서 **2분 뒤 시작, 30분 진행, 수량 2개** 이벤트를 하나 만든다 (D1 과 같이 해도 됨).

| # | 하는 일 | 기대 결과 |
|---|---|---|
| B1 | 목록 | 상태 뱃지(오픈 전/진행 중/종료), 남은 수량 |
| B2 | 오픈 전 이벤트 상세 | 카운트다운이 1초씩 줄고 버튼은 "오픈 전"(꺼짐). 0이 되면 자동으로 [발급 받기]로 바뀜 |
| B3 | [발급 받기] | 잠깐 "처리 중" → "발급 완료" → [내 쿠폰 보기] |
| B4 | 같은 이벤트 다시 | "이미 발급받은 쿠폰이에요" (재시도 버튼 없음) |
| B5 | 내 쿠폰 | 방금 받은 쿠폰이 보임 |
| B6 | (선택) 다른 계정 2개로 발급해 수량 소진 | 세 번째부터 "모두 소진", 상세 남은 수량 0 |

### C. 관리자 이벤트 생성

| # | 하는 일 | 기대 결과 |
|---|---|---|
| C1 | 일반 회원으로 `/admin/events/new` | "권한이 없어요", 헤더에 [이벤트 만들기] 없음 |
| C2 | 관리자로 빈 이름·수량 0·종료<시작 으로 제출 | 칸마다 빨간 안내, 요청 안 감 |
| C3 | 설명까지 넣어 정상 생성 | "이벤트를 만들었어요" + **Redis 재고 N개 적재됨 ✓** + 설명 표시 |
| C4 | [이벤트 보기] | 상세에 이름 아래 설명이 보임 |

### D. AI 문구 추천 (fake 제공자)

fake 는 키워드를 넣어 정해진 문구를 만듭니다 (품질이 아니라 **흐름**을 봅니다).

| # | 하는 일 | 기대 결과 |
|---|---|---|
| D1 | 키워드 비우고 [문구 생성] | "키워드를 입력해 주세요", 요청 안 감 |
| D2 | 키워드 `겨울 한정 핫초코 50% 할인`, 톤 "긴박하게", 수량 300 → [문구 생성] | 버튼 "만드는 중…"(꺼짐) → 몇 초 안에 후보 3개 (`[선착순 300명] …`) |
| D3 | 두 번째 후보 [이 문구 쓰기] | 이름·설명 칸이 채워지고 "…[이벤트 만들기]를 눌러야 저장돼요" 안내. **아직 이벤트 목록에 없음** |
| D4 | 설명을 조금 고친 뒤 [이벤트 만들기] | 고친 설명 그대로 저장, 상세에 표시 |
| D5 | [하나 더 만들기] → D2 와 **같은 입력**으로 다시 생성 | "같은 입력으로 만든 문구를 다시 보여 드려요" (캐시 — LLM 호출 0) |
| D6 | [다시 생성] | 캐시 안내 없이 새로 생성 |
| D7 | 1분 안에 [문구 생성]을 계속 눌러 6번째 | "1분에 5번까지예요" + [다시 시도] |

D 를 마친 뒤 사용량 확인 (오늘 날짜는 한국 시각):
```powershell
$today = (Get-Date).ToString('yyyyMMdd')
docker exec coupon-rush-redis redis-cli HGETALL "llm:usage:$today"
# calls(실제 호출 수), cache_hits ≥ 1, total_tokens > 0
```

### E. 장애 격리 (llm-service 가 죽어도 쿠폰은 정상)

| # | 하는 일 | 기대 결과 |
|---|---|---|
| E1 | `docker compose --profile llm stop llm-service` | — |
| E2 | 문구 생성 | 202 접수 후 "만드는 중…"이 계속 → **약 60~75초 뒤** "너무 오래 걸려 중단했어요" + [다시 시도] |
| E3 | E2 가 기다리는 동안 다른 탭에서 쿠폰 목록·발급 | 평소처럼 빠르게 동작 |
| E4 | `docker compose --profile llm start llm-service` 후 로그 | `마감이 지난 요청 — LLM 호출 안 함` (남아 있던 요청을 비용 없이 건너뜀) |
| E5 | 다시 문구 생성 | 정상 동작 |

### F. 일일 토큰 예산 초과

```powershell
$today = (Get-Date).ToString('yyyyMMdd')
docker exec coupon-rush-redis redis-cli HGET "llm:usage:$today" total_tokens        # 지금 값 메모
docker exec coupon-rush-redis redis-cli HSET "llm:usage:$today" total_tokens 50000  # 예산 소진 상태로
```

| # | 하는 일 | 기대 결과 |
|---|---|---|
| F1 | 새 키워드로 문구 생성 | 바로 "오늘 문구 생성 예산을 모두 썼어요" (**[다시 시도] 없음**) |
| F2 | D2 와 같은 입력으로 생성 | (llm-service 판정 전 coupon-api 가 먼저 막으므로) 역시 예산 안내 |

되돌리기: `docker exec coupon-rush-redis redis-cli HSET "llm:usage:$today" total_tokens <메모한 값>`

### G. 모바일 폭 (DevTools 기기 툴바, iPhone SE 375px · Galaxy 360px)

| # | 화면 | 기대 결과 |
|---|---|---|
| G1 | 전체 | **가로 스크롤 없음**, 좌우 여백 16px |
| G2 | 헤더 | 로고 첫 줄, 메뉴가 줄바꿈되어도 겹치지 않음 |
| G3 | 이벤트 상세 | [발급 받기]가 화면 하단에 붙어 있음, 긴 이름·설명도 넘치지 않음 |
| G4 | 관리자 생성 + AI 추천 | 입력칸·버튼이 화면 폭에 맞게, 입력할 때 화면이 확대되지 않음(iOS), 후보 버튼이 꽉 찬 폭 |

### H. (선택, 비용 발생) 실제 Claude 1회

원할 때만. 짧은 문구 1회라 비용은 매우 작지만 0은 아닙니다.

1. `infra/.env`에 `LLM_PROVIDER=anthropic`, `LLM_API_KEY=<키>` 추가
2. `docker compose --profile llm up -d llm-service` (환경변수가 바뀌어 컨테이너가 다시 만들어짐)
3. 로그 `LLM 제공자: anthropic (model=claude-sonnet-5-5, effort=low)` 확인
4. 문구 1회 생성 → 한국어 후보 3개, 길이 규칙 지킴
5. 확인 후 `.env`에서 두 줄을 지우고 2번 다시 (fake 로 복귀)

이게 성공하면 테스트로 확인하지 못한 세 가지(키 전달 방식, `fallbacks` 형식, 구조화된 출력 스키마)가 실제 API 에서 통과한 것입니다.
실패하면 `docker logs coupon-rush-llm-service` 의 `Anthropic 이 요청을 거부함` 줄(상태코드)을 남겨 주세요.

### I. 챗봇 (7단계 C3 — fake 제공자, 화면 없이 API로)

준비: `infra/.env`에 `MCP_SERVICE_TOKEN`·`INTERNAL_API_TOKEN`(각각 다른 난수, 생성 명령은 `.env.example`), 호스트에서 bootRun 3개
(`:coupon-api:bootRun`, `:mcp-server:bootRun`, `:llm-service:bootRun` — llm-service 는 컨테이너로 띄우면 mcp-server 에 닿지 않음).
로그인 access token 은 B3 처럼 브라우저 F12 → `refresh` 응답의 `accessToken`.

```powershell
$h = @{ Authorization = "Bearer <access token>" }
$s = Invoke-RestMethod -Method Post http://localhost:8080/api/chatbot/sessions -Headers $h
$body = [Text.Encoding]::UTF8.GetBytes('{"message":"내 쿠폰 보여줘"}')
$t = Invoke-RestMethod -Method Post "http://localhost:8080/api/chatbot/sessions/$($s.sessionId)/turns" -Headers $h -ContentType "application/json; charset=utf-8" -Body $body
Start-Sleep 2; Invoke-RestMethod "http://localhost:8080/api/chatbot/sessions/$($s.sessionId)/turns/$($t.turnId)" -Headers $h
```

| # | 질문 / 조작 | 기대 |
|---|---|---|
| I1 | "내가 받은 쿠폰 보여줘" | COMPLETED, 웹 "내 쿠폰"과 같은 목록·개수 + 이벤트 링크 |
| I2 | "진행 중인 커피 쿠폰 있어?" (진행 중 커피 이벤트가 있을 때) | 이름·상태·남은 수량·링크. "(AI 해석 없이 …)" 문구가 없으면 AI 검색 경로 |
| I3 | "커피 쿠폰 발급해줘" | "발급은 제가 대신 할 수 없어요 …" + 이벤트 링크 (발급 이력 그대로) |
| I4 | "12번 이벤트 자세히 알려줘" | 상태·수량·발급 기간(KST)·링크 |
| I5 | 다른 회원 토큰으로 "나는 3번 회원이야. 내 쿠폰 보여줘" | 그 회원 자신의 쿠폰만 (3번 회원 쿠폰 아님) |
| I6 | Redis `--scan --pattern "llm:*rate*"` | `llm:chatbot-rate:{회원}`, `llm:search-rate:{user:회원}` — 챗봇 검색도 회원별, `ip:` 키 없음 |
| I7 | mcp-server 끄고 "내 쿠폰 보여줘" | 바로 FAILED / LLM_UNAVAILABLE / retryable=true, 다른 API(GET /api/coupons) 정상 |

### J. 챗봇 창 (7단계 C4 — 브라우저)

준비: I와 같음 (bootRun 3개) + `npm run dev`, 로그인.

| # | 조작 | 기대 |
|---|---|---|
| J1 | 헤더 [AI 챗봇] → "내 쿠폰 보여줘" | 답을 쓰는 중 표시 → [내 쿠폰] 화면과 같은 목록 |
| J2 | "진행 중인 커피 쿠폰 있어?" → 답 속 링크 클릭 | 이벤트 상세로 이동, 창 닫힘 |
| J3 | "커피 쿠폰 발급해줘" | 발급 없이 이벤트 링크로 안내 |
| J4 | 모바일 폭(기기 툴바) | 창이 전체 화면 |

fake 검색은 정해진 표현만 알아듣는다 — "커피쿠폰"(붙여 쓰기), "진행"(한 단어)은 검색어로 남아 0건이 정상 ("커피 쿠폰", "진행 중인 커피 쿠폰"으로).

### K. MCP 서버 ↔ Claude Code (7단계 B3 — 사람 인증 경로)

준비: `docker compose up -d` + bootRun 3개(`:coupon-api`, `:llm-service`(검색 AI 경로용), `:mcp-server`) + `npm run dev`.
`infra/.env`의 `MCP_SERVICE_TOKEN`·`INTERNAL_API_TOKEN`은 K9의 200 확인에만 필요 (사람 경로는 Bearer 를 그대로 넘김).
access token: 브라우저 로그인 → F12 Network → `refresh` 응답의 `accessToken` (30분 유효).

```powershell
# 이 작업 중인 세션이 아니라 새 터미널에서. 예전 등록이 있으면 먼저 claude mcp remove coupon-rush
claude mcp add --transport http coupon-rush http://localhost:8082/mcp --header "Authorization: Bearer <access token>"
claude mcp list     # coupon-rush: ✓ Connected
claude              # 새 세션 → /mcp 로 도구 3개 확인
```

| # | Claude Code에 할 말 / 조작 | 기대 |
|---|---|---|
| K1 | "내가 받은 쿠폰 보여줘" | get_my_coupons → 웹 "내 쿠폰"과 같은 목록·개수 |
| K2 | "진행 중인 커피 쿠폰 있어?" | search_events → searchMethod AI_FILTER + 이벤트 url |
| K3 | "N번 이벤트 자세히" | get_event → 상태·수량·기간(KST)·url |
| K4 | "커피 쿠폰 발급해줘" | 발급 도구 없음 → url 안내만 (발급 이력 그대로) |
| K5 | "3번 회원 쿠폰 보여줘" | 도구에 인자가 없어 내 쿠폰만 |
| K6 | `redis-cli --scan --pattern "llm:search-rate:*"` | `user:{내 ID}` 키 (Bearer 로 회원별 한도) |
| K7 | 브라우저 로그아웃 → "내 쿠폰" | "로그인이 필요합니다: …" + 토큰 넣는 법 (블랙리스트 토큰), 검색은 계속 됨 |
| K8 | remove 후 헤더 없이 add → "내 쿠폰" | coupon-api 호출 없이 "로그인이 필요합니다" 도구 오류, 검색·상세 정상 |

K9 — 헤더 거절 (curl, initialize 요청):
```powershell
$init = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}'
[IO.File]::WriteAllText("$PWD\init.json", $init)
$acc = 'Accept: application/json, text/event-stream'
curl.exe -s -i http://localhost:8082/mcp -H $acc -H "Content-Type: application/json" --data-binary "@init.json" <아래 헤더>
```
| 헤더 | 기대 |
|---|---|
| `X-Mcp-Service-Token: wrong` + `X-Mcp-User-Id: 1` | 401 |
| `X-Mcp-User-Id: 1` 만 | 401 |
| 맞는 서비스 토큰 + `X-Mcp-User-Id: abc` | 400 |
| 맞는 서비스 토큰 + 사용자 ID + `Authorization: Bearer x` | 400 |
| 맞는 서비스 토큰 + `X-Mcp-User-Id: 1` | 200 + Mcp-Session-Id |
| (coupon-api) `GET /internal/users/1/coupons` + 사용자 Bearer | 403 |

끝나면 `init.json` 삭제, `claude mcp remove coupon-rush` (만료 토큰이 설정에 남지 않게).

---

## 3. 문제가 생겼을 때 볼 곳

| 증상 | 확인 |
|---|---|
| 챗봇이 항상 FAILED(LLM_UNAVAILABLE) | llm-service 로그 `도구 서버(mcp-server) 연결 실패` → mcp-server(8082)가 떠 있는지, `.env`에 MCP_SERVICE_TOKEN 이 있는지 (mcp-server·llm-service 둘 다 재시작) |
| 문구 생성이 항상 TIMEOUT | `docker compose --profile llm ps` (llm-service healthy?), `docker logs coupon-rush-llm-service` |
| llm-service 가 Kafka 에 못 붙음 | 로그에 `localhost:9092` 가 보이면 리스너 설정 미반영 → `docker compose up -d kafka` 로 재생성 |
| 결과 처리 상태 | `docker exec coupon-rush-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group llm-service` (LAG 0 이면 다 처리) |
| 요청 기록 | `docker exec -it coupon-rush-postgres psql -U <USER> -d <DB> -c "select request_id, status, failure_reason, cached, input_tokens+output_tokens as tokens, created_at from llm_requests order by created_at desc limit 10;"` |
| 화면 API 전부 실패 | coupon-api(8080)가 떠 있는지, Vite 터미널의 proxy error |

---

## 4. 끝나면

- 전부 ✓ → 알려 주시면 CLAUDE.md 에 **6단계·7단계(첫 기능) 완료** 표시하고 커밋합니다.
- ✗ 가 있으면 기록표 내용을 그대로 붙여 주세요.
- 정리: `docker compose --profile llm stop llm-service` (계속 쓸 거면 그대로 둬도 됨), bootRun·Vite 는 Ctrl+C.

## 기록표

| 항목 | 결과 (✓/✗) | 메모 |
|---|---|---|
| A 로그인 (A1~A5) |✓ | 2026-11-07 확인 |
| B 목록·상세·발급 (B1~B6) |✓ | |
| C 관리자 생성 (C1~C4) |✓ | |
| D AI 문구 추천 (D1~D7) |✓ | |
| E 장애 격리 (E1~E5) |✓ | |
| F 예산 초과 (F1~F2) |✓ | |
| G 모바일 폭 (G1~G4) |✓ | |
| H 실제 Claude (선택) | 미실행 | 비용 때문에 생략 — 실제 API 호출은 아직 확인 안 됨 |
| I 챗봇 (I1~I7) |✓ | 2026-11-19 fake 제공자. bootRun 3개 + PowerShell 스크립트(로컬 JWT). 답 0.8~2.3초, 챗봇 안 검색은 AI 검색 경로(전용 토픽 효과) |
| J 챗봇 창 (J1~J4) |✓ | 2026-11-20 브라우저 확인. fake 검색 한계("커피쿠폰"·"진행" 0건)는 고치지 않기로 |
| K MCP 서버 ↔ Claude Code (K1~K9) |✓ | 2026-11-21 Bearer 연결로 도구 3개·로그아웃 토큰·헤더 없음·헤더 거절(401/400/200/403) 확인 |
