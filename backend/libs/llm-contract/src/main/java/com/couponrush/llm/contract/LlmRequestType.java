package com.couponrush.llm.contract;

/** LLM 기능 종류. 새 기능(자연어 검색, 챗봇)은 값과 입력 필드를 추가한다. */
public enum LlmRequestType {
    /** 관리자 이벤트 문구(이름·설명) 생성 — 입력은 {@link LlmRequestMessage#eventCopy()} */
    EVENT_COPY
}
