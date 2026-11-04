package com.couponrush.coupon.issue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param strategy              실제 API 가 쓰는 동시성 제어 방식 (기본 REDIS)
 * @param optimisticMaxAttempts 낙관적 락 최대 시도 횟수. 넘으면 COUPON_ISSUE_CONFLICT
 */
@ConfigurationProperties("app.coupon.issue")
public record CouponIssueProperties(
        @DefaultValue("REDIS") IssueStrategy strategy,
        @DefaultValue("100") int optimisticMaxAttempts) {
}
