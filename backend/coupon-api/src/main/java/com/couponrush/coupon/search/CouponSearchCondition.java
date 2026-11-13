package com.couponrush.coupon.search;

import com.couponrush.coupon.CouponStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 이벤트(쿠폰) 검색 조건 — coupon 패키지 안의 표현. LLM 계약(EventSearchFilter)에 의존하지 않게 따로 둔다
 * (변환은 검색 서비스가 한다. 키워드 검색 fallback 도 같은 조건으로 표현된다).
 * 모든 조건은 AND, 비어 있으면 제한 없음. 기간은 [from, to).
 *
 * @param keywords      이름·설명에 들어 있어야 하는 단어 (대소문자 무시, 부분 일치)
 * @param keywordMatch  ALL = 모든 단어 포함 (LLM 조건), ANY = 하나라도 포함 (키워드 검색 — 질문 속 단어가 다 맞을 리 없으므로)
 * @param statuses      진행 상태 중 하나 (조회 시점의 서버 시각 기준)
 * @param availableOnly 남은 수량이 있는 것만 (Redis 재고 기준)
 */
public record CouponSearchCondition(
        List<String> keywords,
        KeywordMatch keywordMatch,
        Set<CouponStatus> statuses,
        Instant startFrom,
        Instant startTo,
        Instant endFrom,
        Instant endTo,
        boolean availableOnly) {

    public enum KeywordMatch { ALL, ANY }

    public CouponSearchCondition {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        keywordMatch = keywordMatch == null ? KeywordMatch.ALL : keywordMatch;
    }

    /** 키워드 검색 (LLM 없이) — 단어 중 하나라도 들어 있는 이벤트 */
    public static CouponSearchCondition anyKeyword(List<String> keywords) {
        return new CouponSearchCondition(keywords, KeywordMatch.ANY, Set.of(), null, null, null, null, false);
    }
}
