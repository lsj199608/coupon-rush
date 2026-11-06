package com.couponrush.coupon.issue.async;

import java.time.Instant;

/**
 * Redis 판정을 통과한 발급 요청. Kafka 토픽 coupon-issue-requests 의 메시지 값(JSON).
 *
 * @param requestId   API 가 만든 UUID — 컨슈머 멱등 처리의 기준, 상태 조회 키
 * @param eventId     쿠폰(이벤트) ID — 메시지 키
 * @param requestedAt Redis 판정 시각. DB 저장 시 발급 기간 검사도 이 시각으로 한다
 *                    (컨슈머가 늦게 처리해서 기간이 지났다고 실패하면 안 되므로)
 */
public record CouponIssueRequestedEvent(String requestId, Long eventId, Long userId, Instant requestedAt) {
}
