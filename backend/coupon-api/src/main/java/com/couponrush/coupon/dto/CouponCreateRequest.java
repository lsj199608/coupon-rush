package com.couponrush.coupon.dto;

import com.couponrush.coupon.Coupon;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * @param description  선택 (최대 500자). 관리자가 직접 쓰거나 LLM 문구 후보를 골라 고친 값 — 서버는 출처를 구분하지 않는다
 * @param issueStartAt ISO-8601 (예: 2026-11-03T10:00:00Z 또는 2026-11-03T19:00:00+09:00)
 */
public record CouponCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = Coupon.DESCRIPTION_MAX) String description,
        @NotNull @Positive @Max(1_000_000) Integer totalQuantity,
        @NotNull Instant issueStartAt,
        @NotNull Instant issueEndAt) {

    @JsonIgnore
    @AssertTrue(message = "발급 시작 시각은 종료 시각보다 앞서야 합니다.")
    public boolean isIssuePeriodValid() {
        return issueStartAt == null || issueEndAt == null || issueStartAt.isBefore(issueEndAt);
    }
}
