package com.couponrush.coupon.issue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param mode                  ASYNC(기본, Redis + Kafka, 202) / SYNC(그 자리에서 DB 저장, 201)
 * @param strategy              SYNC 일 때 쓰는 동시성 제어 방식 (기본 REDIS)
 * @param optimisticMaxAttempts 낙관적 락 최대 시도 횟수. 넘으면 COUPON_ISSUE_CONFLICT
 */
@ConfigurationProperties("app.coupon.issue")
public record CouponIssueProperties(
        @DefaultValue("ASYNC") IssueMode mode,
        @DefaultValue("REDIS") IssueStrategy strategy,
        @DefaultValue("100") int optimisticMaxAttempts) {
}
