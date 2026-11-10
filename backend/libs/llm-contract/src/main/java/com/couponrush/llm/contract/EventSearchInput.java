package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Objects;

/**
 * 자연어 이벤트 검색 입력. 사용자가 검색창에 쓴 문장 그대로 (예: "이번 주에 끝나는 커피 쿠폰").
 * <p>
 * "이번 주", "내일" 같은 상대 시각은 요청의 requestedAt(한국 시각으로 바꿔서)을 기준으로 해석한다 — 그래서 기준 시각 필드를 따로 두지 않는다.
 * 이 값도 프롬프트에 <b>데이터로만</b> 들어간다 (지시문으로 해석되지 않게 하는 것은 llm-service 의 책임).
 *
 * @param query 검색 문장 (1~{@value #QUERY_MAX}자)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventSearchInput(String query) {

    /** 입력 길이 상한 — 프롬프트(=비용) 크기를 묶는다. coupon-api 요청 검증도 이 값을 쓴다 */
    public static final int QUERY_MAX = 200;

    public EventSearchInput {
        Objects.requireNonNull(query, "query");
        if (query.isBlank() || query.length() > QUERY_MAX) {
            throw new IllegalArgumentException("query 는 1~" + QUERY_MAX + "자여야 합니다");
        }
    }
}
