package com.couponrush.coupon.issue;

/** app.coupon.issue.mode (환경변수 COUPON_ISSUE_MODE) */
public enum IssueMode {
    /** Redis 판정 → Kafka 발행 → 202 + requestId. DB 저장은 컨슈머 (기본) */
    ASYNC,
    /** strategy 로 고른 방식으로 그 자리에서 DB 저장 → 201 */
    SYNC
}
