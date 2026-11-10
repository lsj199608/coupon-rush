package com.couponrush.llm.contract;

/** LLM 기능 종류. 새 기능(자연어 검색, 챗봇)은 값과 입력 필드를 추가한다. */
public enum LlmRequestType {
    /** 관리자 이벤트 문구(이름·설명) 생성 — 입력은 {@link LlmRequestMessage#eventCopy()} */
    EVENT_COPY,
    /**
     * 자연어 이벤트 검색 — 입력은 {@link LlmRequestMessage#eventSearch()}, 결과는 {@link LlmResultMessage#searchFilter()}.
     * 값을 추가했으므로 소비자(llm-service)부터 배포한다 (구버전 소비자는 이 값을 못 읽어 DLT 로 보낸다)
     */
    EVENT_SEARCH
}
