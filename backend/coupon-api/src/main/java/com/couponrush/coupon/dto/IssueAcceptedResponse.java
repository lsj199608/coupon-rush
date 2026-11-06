package com.couponrush.coupon.dto;

import com.couponrush.coupon.request.IssueRequestStatus;

/** 202 Accepted 응답. 결과는 GET /api/issue-requests/{requestId} 로 확인 (Location 헤더) */
public record IssueAcceptedResponse(String requestId, Long couponId, IssueRequestStatus status) {
}
