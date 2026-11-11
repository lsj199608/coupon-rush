package com.couponrush.llmservice.client.anthropic;

import com.couponrush.llm.contract.EventSearchStatus;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * 자연어 검색에서 모델에게 요구하는 출력 형식 (구조화된 출력의 JSON 스키마가 된다).
 * <p>
 * 계약(EventSearchFilter)과 모양을 일부러 다르게 둔다:
 * <ul>
 *   <li>기간은 시각(Instant)이 아니라 <b>한국 날짜 문자열, 양끝 포함</b> — 모델에게 "이번 주 = 월요일~일요일"이 자연스럽고,
 *       시간대 계산을 모델에게 맡기지 않는다. [from 0시, to 다음 날 0시) 변환은 검증기가 한다</li>
 *   <li>"조건 없음"은 null 대신 빈 문자열·빈 목록 — 스키마의 모든 필드가 필수라 모델이 항상 같은 모양으로 답한다</li>
 * </ul>
 * 개수·길이·날짜 형식·범위 순서는 스키마가 아니라 {@link EventSearchOutputValidator} 가 검사한다.
 */
public record EventSearchOutput(
        @JsonPropertyDescription("이벤트 이름·설명에서 찾을 핵심 단어. 0~5개, 각 50자 이하. '쿠폰', '이벤트' 같은 일반 단어는 넣지 않는다")
        List<String> keywords,
        @JsonPropertyDescription("진행 상태 조건. 질문에 상태가 드러날 때만 넣고, 아니면 빈 목록")
        List<EventSearchStatus> statuses,
        @JsonPropertyDescription("발급 시작일 범위의 첫날 (YYYY-MM-DD, 한국 날짜, 포함). 조건이 없으면 빈 문자열")
        String startFromDate,
        @JsonPropertyDescription("발급 시작일 범위의 마지막 날 (YYYY-MM-DD, 한국 날짜, 포함). 조건이 없으면 빈 문자열")
        String startToDate,
        @JsonPropertyDescription("발급 종료일 범위의 첫날 (YYYY-MM-DD, 한국 날짜, 포함). 조건이 없으면 빈 문자열")
        String endFromDate,
        @JsonPropertyDescription("발급 종료일 범위의 마지막 날 (YYYY-MM-DD, 한국 날짜, 포함). 조건이 없으면 빈 문자열")
        String endToDate,
        @JsonPropertyDescription("남은 수량이 있는 이벤트만 원하면 true")
        boolean availableOnly) {
}
