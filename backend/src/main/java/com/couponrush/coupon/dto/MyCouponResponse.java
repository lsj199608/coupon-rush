package com.couponrush.coupon.dto;

import java.time.Instant;

public record MyCouponResponse(Long issueId, Long couponId, String couponName, Instant issuedAt) {
}
