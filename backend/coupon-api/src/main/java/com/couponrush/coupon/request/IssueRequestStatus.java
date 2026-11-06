package com.couponrush.coupon.request;

/**
 * 비동기 발급 요청 상태.
 * PENDING: Redis 판정 성공 + Kafka 발행 완료, DB 저장 대기 (Redis 에만 존재)
 * COMPLETED: 컨슈머가 CouponIssue 저장 완료 (DB)
 * FAILED: 컨슈머가 저장하지 못함, Redis 재고는 보상됨 (DB)
 */
public enum IssueRequestStatus {
    PENDING,
    COMPLETED,
    FAILED
}
