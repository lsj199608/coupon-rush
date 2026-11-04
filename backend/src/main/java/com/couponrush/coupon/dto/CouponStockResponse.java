package com.couponrush.coupon.dto;

/** Redis 에 적재된 재고 상태 */
public record CouponStockResponse(Long couponId, int redisStock, long redisIssuedCount) {
}
