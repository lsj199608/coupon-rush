package com.couponrush.coupon.issue;

import com.couponrush.coupon.CouponIssue;

/** 발급 요청 결과. 동기 모드는 바로 발급(Issued → 201), 비동기 모드는 접수(Accepted → 202) */
public sealed interface IssueResult permits IssueResult.Issued, IssueResult.Accepted {

    record Issued(CouponIssue issue) implements IssueResult {
    }

    record Accepted(String requestId, Long couponId) implements IssueResult {
    }
}
