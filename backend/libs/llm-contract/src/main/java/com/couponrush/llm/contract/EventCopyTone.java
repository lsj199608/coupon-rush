package com.couponrush.llm.contract;

/** 이벤트 문구 톤. 자유 입력 대신 고정 값 — 프롬프트 템플릿을 고정할 수 있고 캐시 키도 안정적이다. */
public enum EventCopyTone {
    /** 친근한 */
    FRIENDLY,
    /** 긴박한 (마감 임박, 한정 수량 강조) */
    URGENT,
    /** 고급스러운 */
    PREMIUM,
    /** 재미있는 */
    PLAYFUL
}
