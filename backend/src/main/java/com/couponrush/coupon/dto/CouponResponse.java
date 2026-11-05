package com.couponrush.coupon.dto;

import com.couponrush.coupon.Coupon;
import com.couponrush.coupon.CouponStatus;

import java.time.Instant;

/**
 * 이벤트(쿠폰) 정보.
 *
 * @param remainingQuantity 남은 수량. Redis 재고가 있으면 그 값(발급 판정의 기준이라 가장 정확),
 *                          없으면 DB 기준. 비동기 모드에서 DB 카운터는 컨슈머 처리만큼 늦게 따라온다.
 * @param status            서버 시각 기준 상태 (UPCOMING / OPEN / ENDED)
 * @param serverTime        응답 시점의 서버 시각 — 프론트가 자기 시계와의 차이를 보정해 카운트다운을 서버 기준으로 맞춘다
 */
public record CouponResponse(
        Long id,
        String name,
        int totalQuantity,
        int issuedQuantity,
        int remainingQuantity,
        Instant issueStartAt,
        Instant issueEndAt,
        CouponStatus status,
        Instant serverTime) {

    public static CouponResponse of(Coupon coupon, Integer redisRemaining, Instant now) {
        int remaining = redisRemaining != null ? Math.max(redisRemaining, 0) : coupon.getRemainingQuantity();
        return new CouponResponse(coupon.getId(), coupon.getName(), coupon.getTotalQuantity(),
                coupon.getTotalQuantity() - remaining, remaining,
                coupon.getIssueStartAt(), coupon.getIssueEndAt(), CouponStatus.of(coupon, now), now);
    }
}
