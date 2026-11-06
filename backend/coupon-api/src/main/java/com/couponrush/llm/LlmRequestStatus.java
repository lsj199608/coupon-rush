package com.couponrush.llm;

/** llm_requests.status (CHECK 제약 ck_llm_requests_status 와 같아야 한다 — FlywayMigrationTest) */
public enum LlmRequestStatus {
    PENDING,
    COMPLETED,
    FAILED
}
