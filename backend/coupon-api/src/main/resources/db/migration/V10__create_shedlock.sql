-- ShedLock (8-5): 서버가 여러 대여도 @Scheduled 작업은 한 대에서만 돌게 하는 "실행 표시" 표.
-- 작업 이름마다 한 줄. 잠금 = lock_until 을 미래 시각으로 바꾸는 UPDATE 한 번(몇 ms)이라,
-- 작업이 도는 동안 DB 에 걸려 있는 잠금은 없고 다른 표·요청에는 영향이 없다.
-- 컬럼은 ShedLock 문서의 PostgreSQL 정의 그대로 (usingDbTime 이 TIMESTAMP 에 UTC 시각을 쓴다 — 형식을 바꾸지 않는다)
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
