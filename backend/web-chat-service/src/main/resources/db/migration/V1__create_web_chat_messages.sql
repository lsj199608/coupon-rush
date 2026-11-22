-- 채팅방 메시지 (7단계 D6). 데이터베이스 web_chat — web-chat-service 만 쓴다 (coupon-api 의 coupon_rush 와 별개).
-- 회원 FK 없음: users 는 coupon-api 의 데이터베이스에 있어 참조할 수 없다 → 보낸 때의 닉네임을 복사해 둔다.
-- 이벤트 FK 도 없음 (coupons 도 coupon-api 소유). 없는 이벤트 방은 아무도 보지 않는 빈 방일 뿐이다.
CREATE TABLE web_chat_messages (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- 지난 대화 커서 (저장 순서)
    message_id      UUID         NOT NULL,                             -- 서버가 만든 메시지 ID = 화면에서 실시간 메시지와 겹침 제거
    event_id        BIGINT       NOT NULL,
    sender_id       BIGINT       NOT NULL,
    sender_nickname VARCHAR(50)  NOT NULL,                             -- = 티켓 닉네임 상한 (WebChatTicket.NICKNAME_MAX)
    content         VARCHAR(500) NOT NULL,                             -- = WebChatMessage.CONTENT_MAX. PostgreSQL 은 글자(코드 포인트)로 센다
    sent_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_web_chat_messages_message_id UNIQUE (message_id),    -- 저장 재시도가 두 번 들어가지 않게 (ON CONFLICT DO NOTHING)
    CONSTRAINT ck_web_chat_messages_event_id CHECK (event_id >= 1),
    CONSTRAINT ck_web_chat_messages_content CHECK (content <> '')
);

-- 방의 최근 N개 / 그 이전 페이지: WHERE event_id = ? AND id < ? ORDER BY id DESC LIMIT ?
CREATE INDEX idx_web_chat_messages_event_id_id ON web_chat_messages (event_id, id);
