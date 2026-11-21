-- 이름 정리 (2026-11-21): AI 챗봇은 chatbot, 사람끼리 대화하는 WebSocket 채팅방(7단계 D)은 webChat 으로 구분한다.
-- V8 의 chat_* 는 챗봇 대화라서 chatbot_* 로 바꾼다. 데이터·구조는 그대로 — 이름(테이블·시퀀스·인덱스·제약)과 요청 종류 값만.
-- (이미 적용된 V8 은 고치지 않는다 — Flyway 규칙)

-- 요청 종류 CHAT → CHATBOT (계약 LlmRequestType 과 같게. 제약을 먼저 넓혔다가 값을 바꾸고 다시 좁힌다)
ALTER TABLE llm_requests DROP CONSTRAINT ck_llm_requests_type;
ALTER TABLE llm_requests ADD CONSTRAINT ck_llm_requests_type CHECK (type IN ('EVENT_COPY', 'EVENT_SEARCH', 'CHAT', 'CHATBOT'));
UPDATE llm_requests SET type = 'CHATBOT' WHERE type = 'CHAT';
ALTER TABLE llm_requests DROP CONSTRAINT ck_llm_requests_type;
ALTER TABLE llm_requests ADD CONSTRAINT ck_llm_requests_type CHECK (type IN ('EVENT_COPY', 'EVENT_SEARCH', 'CHATBOT'));

-- 대화방
ALTER TABLE chat_sessions RENAME TO chatbot_sessions;
ALTER SEQUENCE chat_sessions_id_seq RENAME TO chatbot_sessions_id_seq;
ALTER INDEX chat_sessions_pkey RENAME TO chatbot_sessions_pkey;
ALTER INDEX idx_chat_sessions_user_last RENAME TO idx_chatbot_sessions_user_last;
ALTER TABLE chatbot_sessions RENAME CONSTRAINT chat_sessions_user_id_fkey TO chatbot_sessions_user_id_fkey;

-- 턴
ALTER TABLE chat_turns RENAME TO chatbot_turns;
ALTER SEQUENCE chat_turns_id_seq RENAME TO chatbot_turns_id_seq;
ALTER INDEX chat_turns_pkey RENAME TO chatbot_turns_pkey;
ALTER INDEX chat_turns_llm_request_id_key RENAME TO chatbot_turns_llm_request_id_key;
ALTER INDEX idx_chat_turns_session RENAME TO idx_chatbot_turns_session;
ALTER TABLE chatbot_turns RENAME CONSTRAINT chat_turns_session_id_fkey TO chatbot_turns_session_id_fkey;
ALTER TABLE chatbot_turns RENAME CONSTRAINT chat_turns_llm_request_id_fkey TO chatbot_turns_llm_request_id_fkey;
ALTER TABLE chatbot_turns RENAME CONSTRAINT ck_chat_turns_question TO ck_chatbot_turns_question;
