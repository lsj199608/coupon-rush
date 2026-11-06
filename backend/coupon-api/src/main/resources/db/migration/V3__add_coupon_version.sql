-- 낙관적 락(@Version)용 버전 컬럼.
-- 조건부 UPDATE 방식도 이 값을 함께 올린다 → 어떤 방식으로 바꾼 행이든 낙관적 락이 변경을 감지할 수 있다.
ALTER TABLE coupons ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
