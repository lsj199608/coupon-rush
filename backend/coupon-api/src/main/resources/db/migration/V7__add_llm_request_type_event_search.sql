-- 자연어 이벤트 검색 (7단계 A). 요청 종류 EVENT_SEARCH 추가.
-- CHECK 제약 값 목록은 LlmRequestType 과 같아야 한다 (FlywayMigrationTest 가 비교).
-- 결과(result JSONB)는 EVENT_COPY = 후보 목록, EVENT_SEARCH = 검색 조건 — 컬럼은 그대로 쓴다.
ALTER TABLE llm_requests DROP CONSTRAINT ck_llm_requests_type;
ALTER TABLE llm_requests ADD CONSTRAINT ck_llm_requests_type CHECK (type IN ('EVENT_COPY', 'EVENT_SEARCH'));
