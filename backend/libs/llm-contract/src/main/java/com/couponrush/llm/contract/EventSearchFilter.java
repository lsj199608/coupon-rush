package com.couponrush.llm.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 자연어 검색 결과 = LLM 이 질문에서 뽑아낸 <b>검색 조건</b>. 이벤트 목록 자체가 아니다.
 * <p>
 * LLM 은 조건만 만들고 실제 조회는 coupon-api 가 DB 로 한다 → 없는 이벤트를 지어낼 수 없고, 조건은 검증할 수 있다.
 * 모든 조건은 AND 로 묶이고, 비어 있는 조건은 "제한 없음"이다. 모든 조건이 비면 {@link #isEmpty()}
 * (예: "안녕" 처럼 검색 의도가 없는 문장 — 어떻게 보여 줄지는 coupon-api 가 정한다).
 * <p>
 * 기간 조건은 발급 기간 기준이고 [from, to) — from 포함, to 미포함 (쿠폰 발급 기간 규칙과 같다).
 *
 * @param keywords      이름·설명에서 찾을 단어 (0~{@value #KEYWORD_COUNT_MAX}개, 각 1~{@value #KEYWORD_MAX}자). 여러 개면 모두 포함(AND)
 * @param statuses      진행 상태 (비면 전부). 여러 개면 그중 하나(OR)
 * @param startFrom     발급 시작이 이 시각 이후 (null 이면 제한 없음)
 * @param startTo       발급 시작이 이 시각 이전
 * @param endFrom       발급 종료가 이 시각 이후 — "이번 주에 끝나는" = endFrom 지금 ~ endTo 다음 주 월요일 0시
 * @param endTo         발급 종료가 이 시각 이전
 * @param availableOnly true 면 남은 수량이 있는 것만
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventSearchFilter(
        List<String> keywords,
        Set<EventSearchStatus> statuses,
        Instant startFrom,
        Instant startTo,
        Instant endFrom,
        Instant endTo,
        boolean availableOnly
) {

    /** 단어 수 상한 — 조건이 많을수록 SQL 이 무거워지고, 5개를 넘는 질문은 사실상 없다 */
    public static final int KEYWORD_COUNT_MAX = 5;
    /** 단어 하나 길이 상한 */
    public static final int KEYWORD_MAX = 50;

    public EventSearchFilter {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        if (keywords.size() > KEYWORD_COUNT_MAX) {
            throw new IllegalArgumentException("keywords 는 최대 " + KEYWORD_COUNT_MAX + "개입니다");
        }
        for (String keyword : keywords) {
            if (keyword.isBlank() || keyword.length() > KEYWORD_MAX) {
                throw new IllegalArgumentException("keyword 는 1~" + KEYWORD_MAX + "자여야 합니다");
            }
        }
        requireOrdered(startFrom, startTo, "startFrom 은 startTo 보다 앞이어야 합니다");
        requireOrdered(endFrom, endTo, "endFrom 은 endTo 보다 앞이어야 합니다");
    }

    /** 조건 없음 */
    public static EventSearchFilter empty() {
        return new EventSearchFilter(List.of(), Set.of(), null, null, null, null, false);
    }

    /** 조건이 하나도 없는가 (JSON 에는 싣지 않는 계산 값) */
    @JsonIgnore
    public boolean isEmpty() {
        return keywords.isEmpty() && statuses.isEmpty()
                && startFrom == null && startTo == null && endFrom == null && endTo == null
                && !availableOnly;
    }

    private static void requireOrdered(Instant from, Instant to, String message) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException(message);
        }
    }
}
