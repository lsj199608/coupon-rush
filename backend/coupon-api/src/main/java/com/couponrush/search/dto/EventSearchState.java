package com.couponrush.search.dto;

/** 검색 응답 상태. FALLBACK 도 결과가 있는 정상 응답이다 (검색은 AI 없이도 동작해야 하므로) */
public enum EventSearchState {
    PENDING,
    COMPLETED,
    FALLBACK
}
