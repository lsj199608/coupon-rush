package com.couponrush.coupon.issue.redis;

/** coupon-issue.lua 의 반환값 */
public enum RedisIssueResult {
    SUCCESS,
    DUPLICATE,
    SOLD_OUT,
    NOT_IN_PERIOD,
    /** 재고가 Redis 에 적재되지 않음 (없는 쿠폰이거나 Redis 데이터 유실) */
    NOT_READY
}
