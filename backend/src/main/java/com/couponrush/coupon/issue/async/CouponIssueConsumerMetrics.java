package com.couponrush.coupon.issue.async;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 컨슈머 신뢰성 관측용 카운터.
 * - failedDeliveries: 처리에 실패한 "시도" 수 (최초 시도 포함). 재시도 3회 후 DLT 로 간 메시지는 4, 재시도 없이 DLT 로 간 메시지는 1
 * - dltPublished: DLT 로 보낸 메시지 수 — 0 이 아니면 사람이 봐야 한다
 * - duplicatesSkipped: 이미 처리된 요청이라 건너뛴 메시지 수 (멱등 처리)
 * 운영에서는 Micrometer 지표로 바꿔 알림을 건다.
 */
@Component
public class CouponIssueConsumerMetrics {

    private final AtomicLong failedDeliveries = new AtomicLong();
    private final AtomicLong dltPublished = new AtomicLong();
    private final AtomicLong duplicatesSkipped = new AtomicLong();

    void failedDelivery() { failedDeliveries.incrementAndGet(); }
    void publishedToDlt() { dltPublished.incrementAndGet(); }
    void skippedDuplicate() { duplicatesSkipped.incrementAndGet(); }

    public long failedDeliveries() { return failedDeliveries.get(); }
    public long dltPublished() { return dltPublished.get(); }
    public long duplicatesSkipped() { return duplicatesSkipped.get(); }
}
