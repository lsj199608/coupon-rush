package com.couponrush.llm.contract;

/** 결과 메시지 상태. PENDING 은 coupon-api 내부 상태라 메시지로는 오지 않는다. */
public enum LlmResultStatus {
    COMPLETED,
    FAILED
}
