package com.couponrush.search.dto;

import com.couponrush.llm.contract.EventSearchInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/coupons/search 바디. 길이 상한은 계약(EventSearchInput.QUERY_MAX)과 같다 */
public record EventSearchRequest(
        @NotBlank @Size(max = EventSearchInput.QUERY_MAX) String query) {

    /** 앞뒤 공백 제거, 연속 공백은 하나로 (같은 질문이 같은 캐시 키가 되는 것은 llm-service 가 따로 보장) */
    public String normalizedQuery() {
        return query.strip().replaceAll("\\s+", " ");
    }
}
