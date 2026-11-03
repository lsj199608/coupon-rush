package com.couponrush.coupon.dto;

import com.couponrush.coupon.CouponIssue;

import java.time.Instant;

public record CouponIssueResponse(Long issueId, Long couponId, Instant issuedAt) {

    public static CouponIssueResponse from(CouponIssue issue) {
        return new CouponIssueResponse(issue.getId(), issue.getCouponId(), issue.getIssuedAt());
    }
}
