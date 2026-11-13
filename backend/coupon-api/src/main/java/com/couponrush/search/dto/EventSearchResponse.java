package com.couponrush.search.dto;

import com.couponrush.coupon.dto.CouponResponse;
import com.couponrush.llm.contract.EventSearchFilter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 검색 응답 (접수·폴링 공통). 화면은 status 하나로 분기한다.
 *
 * @param requestId      LLM 검색 요청 ID (폴링 주소). 접수부터 키워드 검색으로 끝났으면 null
 * @param status         PENDING = AI 가 조건을 만드는 중(events 비어 있음) / COMPLETED = AI 조건으로 찾음 / FALLBACK = 키워드 검색으로 찾음
 * @param query          검색한 질문
 * @param filter         COMPLETED: AI 가 만든 조건 (화면에 "이렇게 찾았어요"로 보여 줄 수 있게). 그 외 null
 * @param fallbackReason FALLBACK 일 때 왜 키워드 검색이 됐는지. 그 외 null
 * @param keywords       FALLBACK: 질문에서 뽑은 단어 (하나라도 들어 있으면 결과). 그 외 빈 목록
 * @param events         결과 (목록 API 와 같은 형식, 최대 50개)
 * @param deadlineAt     PENDING: 이 시각까지 AI 결과가 없으면 키워드 검색이 된다 (화면 폴링 타임아웃 기준). 그 외 null
 */
public record EventSearchResponse(
        UUID requestId,
        EventSearchState status,
        String query,
        EventSearchFilter filter,
        SearchFallbackReason fallbackReason,
        List<String> keywords,
        List<CouponResponse> events,
        Instant deadlineAt) {

    public static EventSearchResponse pending(UUID requestId, String query, Instant deadlineAt) {
        return new EventSearchResponse(requestId, EventSearchState.PENDING, query, null, null, List.of(), List.of(), deadlineAt);
    }

    public static EventSearchResponse completed(UUID requestId, String query, EventSearchFilter filter, List<CouponResponse> events) {
        return new EventSearchResponse(requestId, EventSearchState.COMPLETED, query, filter, null, List.of(), events, null);
    }

    public static EventSearchResponse fallback(UUID requestId, String query, SearchFallbackReason reason,
                                               List<String> keywords, List<CouponResponse> events) {
        return new EventSearchResponse(requestId, EventSearchState.FALLBACK, query, null, reason, keywords, events, null);
    }
}
