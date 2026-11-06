package com.couponrush.coupon.request;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IssueRequestResponse(
        String requestId,
        Long couponId,
        IssueRequestStatus status,
        Long couponIssueId,
        String failureReason,
        Instant requestedAt,
        Instant processedAt) {

    static IssueRequestResponse from(IssueRequest request) {
        return new IssueRequestResponse(request.getRequestId(), request.getCouponId(), request.getStatus(),
                request.getCouponIssueId(), request.getFailureReason(), request.getRequestedAt(), request.getProcessedAt());
    }

    static IssueRequestResponse pending(PendingIssueRequestStore.PendingIssueRequest pending) {
        return new IssueRequestResponse(pending.requestId(), pending.couponId(), IssueRequestStatus.PENDING,
                null, null, pending.requestedAt(), null);
    }
}
