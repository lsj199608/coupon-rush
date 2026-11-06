package com.couponrush.llm.dto;

import com.couponrush.llm.contract.EventCopyInput;
import com.couponrush.llm.contract.EventCopyTone;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * POST /api/admin/llm/event-copy 요청. 관리자 이벤트 생성 폼에 넣은 값 그대로 — 기간은 아직 비어 있어도 된다.
 * 검증 규칙은 계약(EventCopyInput)과 쿠폰 생성(CouponCreateRequest)에 맞춘다.
 *
 * @param keywords   핵심어·설명 (예: "겨울 한정 핫초코 50% 할인")
 * @param tone       FRIENDLY / URGENT / PREMIUM / PLAYFUL
 * @param regenerate true 면 같은 입력의 캐시된 문구 대신 새로 생성 ("다시 생성")
 */
public record EventCopyRequest(
        @NotBlank @Size(max = EventCopyInput.KEYWORDS_MAX) String keywords,
        @NotNull EventCopyTone tone,
        @NotNull @Positive @Max(1_000_000) Integer totalQuantity,
        Instant issueStartAt,
        Instant issueEndAt,
        boolean regenerate) {

    @JsonIgnore
    @AssertTrue(message = "발급 시작 시각은 종료 시각보다 앞서야 합니다.")
    public boolean isIssuePeriodValid() {
        return issueStartAt == null || issueEndAt == null || issueStartAt.isBefore(issueEndAt);
    }

    public EventCopyInput toInput() {
        return new EventCopyInput(keywords.strip(), tone, totalQuantity, issueStartAt, issueEndAt);
    }
}
