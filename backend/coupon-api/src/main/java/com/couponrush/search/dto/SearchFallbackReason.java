package com.couponrush.search.dto;

import com.couponrush.llm.contract.LlmFailureReason;

/**
 * AI 검색 대신 키워드 검색을 한 이유 (화면 안내용). LLM 실패 사유를 사용자에게 그대로 보이지 않게 몇 가지로 묶는다.
 */
public enum SearchFallbackReason {
    /** 검색 한도(분·일) 초과 — LLM 요청을 만들지 않았다 */
    RATE_LIMITED,
    /** 오늘 LLM 토큰 예산 소진 */
    BUDGET_EXCEEDED,
    /** 마감 안에 AI 결과가 없음 (llm-service 중단·지연) */
    TIMEOUT,
    /** 그 밖의 실패 (접수 발행 실패, LLM 장애·거절·형식 오류 등) */
    LLM_UNAVAILABLE;

    public static SearchFallbackReason of(LlmFailureReason reason) {
        return switch (reason) {
            case TIMEOUT -> TIMEOUT;
            case BUDGET_EXCEEDED -> BUDGET_EXCEEDED;
            default -> LLM_UNAVAILABLE;
        };
    }
}
