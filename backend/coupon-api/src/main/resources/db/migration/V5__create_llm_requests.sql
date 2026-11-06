-- LLM 요청 (7단계). coupon-api 가 소유한다 — llm-service 는 DB 가 없고 결과를 Kafka(llm-results)로 돌려준다.
-- 발급(issue_requests)과 달리 PENDING 부터 DB 에 쓴다: 분당 몇 건이라 쓰기 부담이 없고,
-- 상태 조회·타임아웃 정리가 DB 한 곳에서 끝난다.
-- 상태 전이는 조건부 UPDATE(WHERE status = 'PENDING')로만 한다 → 늦은 결과·중복 결과·타임아웃이 경합해도 먼저 온 하나만 반영.
CREATE TABLE llm_requests
(
    request_id     UUID                        PRIMARY KEY,   -- coupon-api 가 만든 UUID = Kafka 메시지 키, 멱등 기준
    type           VARCHAR(30)                 NOT NULL,      -- LlmRequestType
    requested_by   BIGINT                      NOT NULL,      -- 회원 ID. FK 없음: 감사 기록이라 회원이 지워져도 남긴다
    input          JSONB                       NOT NULL,      -- 요청 입력 (재현·디버깅용)
    status         VARCHAR(20)                 NOT NULL,
    result         JSONB,                                     -- COMPLETED: 생성 후보 목록
    failure_reason VARCHAR(50),                               -- FAILED: LlmFailureReason
    model          VARCHAR(100),                              -- 실제 호출한 모델 (fake 포함)
    input_tokens   INT                         NOT NULL DEFAULT 0,
    output_tokens  INT                         NOT NULL DEFAULT 0,
    cached         BOOLEAN                     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    deadline_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,      -- 이 시각까지 결과가 없으면 FAILED(TIMEOUT)
    completed_at   TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT ck_llm_requests_type CHECK (type IN ('EVENT_COPY')),
    CONSTRAINT ck_llm_requests_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_llm_requests_failure_reason CHECK (failure_reason IN
        ('LLM_UNAVAILABLE', 'BUDGET_EXCEEDED', 'INVALID_OUTPUT', 'CONTENT_REFUSED', 'TIMEOUT', 'PUBLISH_FAILED')),
    CONSTRAINT ck_llm_requests_completed CHECK (status <> 'COMPLETED' OR (result IS NOT NULL AND failure_reason IS NULL)),
    CONSTRAINT ck_llm_requests_failed CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL),
    CONSTRAINT ck_llm_requests_tokens CHECK (input_tokens >= 0 AND output_tokens >= 0)
);

-- 타임아웃 정리 (PENDING 중 마감 지난 것) — 부분 인덱스라 완료된 행이 쌓여도 작다
CREATE INDEX idx_llm_requests_pending_deadline ON llm_requests (deadline_at) WHERE status = 'PENDING';
-- 사용자별 요청 기록 조회
CREATE INDEX idx_llm_requests_requested_by ON llm_requests (requested_by, created_at);
