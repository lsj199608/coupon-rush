package com.couponrush.coupon.dto;

import com.couponrush.coupon.Coupon;

import java.time.Instant;

public record CouponResponse(
        Long id,
        String name,
        int totalQuantity,
        int issuedQuantity,
        int remainingQuantity,
        Instant issueStartAt,
        Instant issueEndAt) {

    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(coupon.getId(), coupon.getName(), coupon.getTotalQuantity(),
                coupon.getIssuedQuantity(), coupon.getRemainingQuantity(),
                coupon.getIssueStartAt(), coupon.getIssueEndAt());
    }
}
