package com.couponrush.mcpserver.couponapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * coupon-api 응답 중 도구가 쓰는 필드만 (모르는 필드는 무시 — coupon-api 가 필드를 추가해도 깨지지 않게).
 * coupon-api 코드를 공유하지 않고 HTTP 응답 형식만 따른다 (모듈 간 공유는 계약뿐이라는 규칙. 이 형식은 공개 REST API).
 */
public final class CouponApiDtos {

    private CouponApiDtos() {
    }

    /** GET /api/coupons/{id}, 검색 결과의 events[] */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CouponEvent(long id, String name, String description, int totalQuantity, int remainingQuantity,
                              Instant issueStartAt, Instant issueEndAt, String status) {
    }

    /** AI 가 만든 검색 조건 (COMPLETED) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchFilter(List<String> keywords, List<String> statuses, Instant startFrom, Instant startTo,
                               Instant endFrom, Instant endTo, boolean availableOnly) {
    }

    /** POST /api/coupons/search, GET /api/coupons/search/{id} — status: PENDING / COMPLETED / FALLBACK */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResult(String requestId, String status, String query, SearchFilter filter, String fallbackReason,
                               List<String> keywords, List<CouponEvent> events) {

        public boolean isPending() {
            return "PENDING".equals(status);
        }
    }

    /** coupon-api 에러 응답 {"code","message"} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiError(String code, String message) {
    }
}
