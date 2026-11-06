package com.couponrush.coupon;

import java.time.Instant;

/** 이벤트 진행 상태 — 서버 시각 기준으로 계산한다 (발급 기간은 [start, end)) */
public enum CouponStatus {
    /** 오픈 전 */
    UPCOMING,
    /** 진행 중 */
    OPEN,
    /** 종료 */
    ENDED;

    public static CouponStatus of(Coupon coupon, Instant now) {
        if (now.isBefore(coupon.getIssueStartAt())) {
            return UPCOMING;
        }
        return now.isBefore(coupon.getIssueEndAt()) ? OPEN : ENDED;
    }
}
