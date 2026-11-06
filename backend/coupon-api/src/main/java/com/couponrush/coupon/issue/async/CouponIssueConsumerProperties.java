package com.couponrush.coupon.issue.async;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 컨슈머 재시도 (지수 백오프). 기본: 0.5s → 1s → 2s 후 3번 모두 실패하면 DLT.
 *
 * @param maxRetries      최초 시도 이후 재시도 횟수
 * @param initialInterval 첫 재시도까지 대기
 * @param multiplier      다음 대기 = 이전 대기 × multiplier
 * @param maxInterval     대기 상한
 */
@ConfigurationProperties("app.kafka.consumer.retry")
public record CouponIssueConsumerProperties(
        @DefaultValue("3") int maxRetries,
        @DefaultValue("500ms") Duration initialInterval,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("5s") Duration maxInterval) {
}
