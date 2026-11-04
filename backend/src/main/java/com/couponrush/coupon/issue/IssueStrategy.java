package com.couponrush.coupon.issue;

/** 재고 동시성 제어 방식. app.coupon.issue.strategy (환경변수 COUPON_ISSUE_STRATEGY) 로 선택 */
public enum IssueStrategy {
    /** UPDATE ... SET issued = issued + 1 WHERE issued < total (DB 기본값) */
    CONDITIONAL_UPDATE,
    /** SELECT ... FOR UPDATE 로 행을 잠그고 확인 → 차감 */
    PESSIMISTIC_LOCK,
    /** @Version 으로 충돌 감지 → 재시도 */
    OPTIMISTIC_LOCK,
    /** Redis Lua 스크립트로 판정·차감, 성공한 요청만 DB 저장 */
    REDIS
}
