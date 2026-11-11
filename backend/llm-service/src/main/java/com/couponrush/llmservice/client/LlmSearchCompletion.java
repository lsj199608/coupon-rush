package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.EventSearchFilter;

import java.util.Objects;

/** 자연어 검색 성공 결과. filter 는 계약 규칙을 이미 통과한 값 (비어 있을 수 있다 — 검색 의도가 없는 문장) */
public record LlmSearchCompletion(EventSearchFilter filter, String model, int inputTokens, int outputTokens) {

    public LlmSearchCompletion {
        Objects.requireNonNull(filter, "filter");
    }
}
