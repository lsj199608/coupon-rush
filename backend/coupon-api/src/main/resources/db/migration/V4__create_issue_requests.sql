-- 비동기 발급 요청의 최종 결과 (COMPLETED / FAILED). 컨슈머가 기록한다.
-- PENDING(처리 대기)은 요청 경로에서 DB 를 쓰지 않으려고 Redis(issue-request:{id})에만 둔다.
CREATE TABLE issue_requests
(
    request_id      VARCHAR(36)                 PRIMARY KEY,   -- UUID. 컨슈머 멱등 처리의 기준
    coupon_id       BIGINT                      NOT NULL REFERENCES coupons (id),
    user_id         BIGINT                      NOT NULL,      -- FK 없음: 회원 FK 위반으로 실패한 요청도 FAILED 로 남기기 위해
    status          VARCHAR(20)                 NOT NULL,
    coupon_issue_id BIGINT REFERENCES coupon_issues (id),
    failure_reason  VARCHAR(50),
    requested_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    processed_at    TIMESTAMP(6) WITH TIME ZONE,

    CONSTRAINT ck_issue_requests_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_issue_requests_completed CHECK (status <> 'COMPLETED' OR coupon_issue_id IS NOT NULL)
);

CREATE INDEX idx_issue_requests_user_id ON issue_requests (user_id);
