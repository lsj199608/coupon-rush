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

### L. 이벤트 채팅 (7단계 D4 — 브라우저 창 2개)

준비: coupon-api(8080) + **web-chat-service(8083, `.\gradlew :web-chat-service:bootRun`)** + `npm run dev`.
창 A = 평소 브라우저에서 로그인, 창 B = 시크릿 창에서 **다른 제공자**로 로그인(같은 회원이면 "나"로만 보임). 둘 다 같은 이벤트 상세 화면.

| # | 조작 | 기대 |
|---|---|---|
| L1 | 두 창에서 같은 이벤트 상세 | 채팅 칸 "연결됨", DevTools Network → `POST /api/web-chat/tickets` 200(응답 Cache-Control: no-store) 후 `ws/web-chat` 101 |
| L2 | A에서 "안녕하세요" Enter | A에는 오른쪽 "나", B에는 왼쪽 A의 닉네임으로 바로 나타남. Shift+Enter 는 줄바꿈 |
| L3 | B에서 답장 | A에 바로 나타남 |
| L4 | A에서 빠르게 6개 연타 | 6번째부터 "너무 빨리 보내고 있어요" (10초에 5개), 10초 뒤 다시 보내짐 |
| L5 | B 새로고침 | 지난 대화가 그대로 보임 (DB 저장분) |
| L6 | 메시지를 51개 이상 쌓고 새로고침 → 맨 위 [이전 대화 보기] | 더 오래된 대화가 위에 붙고 보던 위치가 튀지 않음 |
| L7 | 로그아웃한 창(또는 세 번째 시크릿 창, 비로그인) | 대화는 실시간으로 보이고 입력칸 대신 "로그인하면 대화에 참여할 수 있어요" |
| L8 | web-chat-service Ctrl+C | 두 창 모두 "연결이 끊겼어요. 다시 연결하는 중…", 보내기 꺼짐. 발급·목록 등 다른 화면은 정상 |
| L9 | 끊긴 동안 다시 bootRun → 기다림 | 자동으로 "연결됨"(새 티켓으로 — Network에 tickets 요청이 다시), 다시 대화 가능 |
| L10 | `<b>굵게</b>` 보내기 | 글자 그대로 보임 (HTML 로 해석되지 않음) |
| L11 | 모바일 폭(기기 툴바) | 채팅 칸이 화면 폭 전체, 입력 글자 16px(확대 없음) |

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

## 5. 로컬 Kubernetes에서 (8단계 8-6)

위 A~L 을 **클러스터(http://localhost, 게이트웨이 경유)** 에서 다시 본다. 앱 코드는 같고 바뀐 것은 띄우는 방식·주소·여러 대(8-5)다.
LLM 은 fake 그대로(H 제외). 클러스터 DB·Redis 는 compose 와 별개 — 관리자는 클러스터 DB 에서 따로 바꾼다.

**띄우기** (README "로컬 Kubernetes로 실행"): 이미지 빌드 → `powershell -ExecutionPolicy Bypass -File infra/k8s/local-setup.ps1` → `kubectl apply -k infra/k8s/overlays/local`
→ `kubectl get pods -n coupon-rush` 가 모두 Ready. compose·bootRun·Vite 는 띄우지 않는다.

**명령 바꿔 읽기**

| 위 절차 | 클러스터 |
|---|---|
| http://localhost:5173 / :8080 | **http://localhost** (화면·API 모두, 게이트웨이) |
| `docker exec coupon-rush-redis redis-cli …` | `kubectl exec -n coupon-rush redis-0 -- redis-cli …` |
| psql | `kubectl exec -it -n coupon-rush postgres-0 -- psql -U <POSTGRES_USER> -d <POSTGRES_DB>` |
| E1 llm-service 끄기 / E4 켜기 | `kubectl scale deploy/llm-service -n coupon-rush --replicas=0` / `=1`, 로그 `kubectl logs -n coupon-rush deploy/llm-service` |
| I7 mcp-server 끄기 | `kubectl scale deploy/mcp-server -n coupon-rush --replicas=0` (끝나면 `=1`) |
| L8 채팅 서버 Ctrl+C / L9 다시 bootRun | `kubectl scale deploy/web-chat-service -n coupon-rush --replicas=0` / `=2` |
| K MCP 연결 | `kubectl port-forward -n coupon-rush svc/mcp-server 8082:8082` 를 켜 둔 채 같은 명령 |
| 이미지를 다시 빌드했을 때 | `kubectl rollout restart deploy/<앱> -n coupon-rush` (같은 `:local` 태그라 자동으로 안 바뀜) |

**새 항목 (8-4·8-5에서 넘어온 것)**

| # | 하는 일 | 기대 결과 |
|---|---|---|
| M1 | **네이버** 로그인 (네이버 콘솔에 `http://localhost/login/oauth2/code/naver` 추가 등록 후) | A1 과 같음 |
| M2 | 채팅 창 2개 연결 → `kubectl get pods -n coupon-rush -l app.kubernetes.io/name=web-chat-service` 중 **하나만** 삭제 | 그 Pod 에 붙어 있던 창만 "다시 연결하는 중…" → 몇 초 뒤 "연결됨"(살아 있는 Pod), 다른 창은 끊기지 않음. 끊긴 사이 오간 메시지도 지난 대화로 보임 |
| M3 | 두 창이 서로 다른 Pod 에 붙은 상태에서 대화 (Pod 별 로그로 확인) | 서로 바로 보임 (서버 간 전달 D3) |
| M4 | 발급 중 coupon-api rolling update: 수량 N 이벤트에 여러 회원이 발급하는 동안 `kubectl rollout restart deploy/coupon-api -n coupon-rush` | 실패(5xx) 없음, 발급 수 ≤ N, 대조 API `GET /api/admin/coupons/{id}/reconciliation` 일치 |
| M5 | `SELECT name, locked_by, locked_at FROM shedlock;` (몇 분 간격 2번) | 작업마다 한 Pod 이름, 타임아웃 정리는 30초마다 갱신 |
| M6 | 상세 화면을 보며 F12 Network → `/api/coupons/{id}` 응답 헤더 | `X-Cache-Status` MISS → HIT (게이트웨이 1초 캐시) |

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
| L 이벤트 채팅 (L1~L11) |✓ | 2026-11-24 브라우저 창 2개(일반 + 시크릿, 다른 제공자). 티켓·송수신·도배 거절·지난 대화·비로그인 읽기·채팅 서버 재기동 후 자동 재연결·모바일 폭 |

**로컬 Kubernetes (8-6)** — 5절 명령으로

| 항목 | 결과 (✓/✗) | 메모 |
|---|---|---|
| A 로그인 (카카오·구글) | | |
| M1 네이버 로그인 | | |
| B 목록·상세·발급 | | |
| C 관리자 생성 | | |
| D AI 문구 추천 | | |
| E 장애 격리 (llm-service scale 0) | | |
| F 예산 초과 | | |
| G 모바일 폭 | | |
| I 챗봇 (API) | | |
| J 챗봇 창 | | |
| K MCP (port-forward, 선택) | | |
| L 이벤트 채팅 |✓ | L1~L7·L10·L11 ✓ 2026-11-27 브라우저(http://localhost). L8·L9 ✓ 2026-11-27 (scale 0 → 두 창 "다시 연결하는 중…" 유지·목록 200·지난 대화 502 / scale 2 → 47초 뒤 Ready, 새로고침 없이 자동 재연결·티켓 재요청·대화 OK) |
| M2~M3 채팅 Pod 하나 삭제·서버 간 전달 |✓ | 2026-11-27 창 A·B를 다른 Pod에 붙여(Pod별 /proc/net/tcp 연결 수로 확인) 대화 → B의 Pod 삭제 → B만 재연결, A 유지, 끊긴 사이 메시지 보임. 연결 분배는 무작위라 B 새로고침 3번 모두 같은 Pod → A의 Pod 라벨을 잠깐 바꿔 Service에서 빼는 방법으로 갈라 놓음 |
| M4 발급 중 rolling update | | |
| M5 ShedLock |✓ | 2026-11-27 (8-5) 2대에서 타임아웃 정리 2분간 5회 모두 한 Pod(locked_by), 사라진 Pod의 잠금은 lock_until 뒤 다른 Pod가 이어받음 |
| M6 상세 캐시 | | |
