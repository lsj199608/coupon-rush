package com.couponrush.llm.contract;

/**
 * LLM 요청 실패 사유. 결과 메시지로 오는 것(llm-service)과 coupon-api 가 직접 정하는 것이 함께 있다 —
 * llm_requests.failure_reason 과 화면 메시지(frontend api/errors.ts)가 이 한 목록을 기준으로 한다.
 */
public enum LlmFailureReason {

    // ── llm-service 가 결과 메시지로 보냄 ──

    /** 재시도를 다 써도 실패, 또는 서킷 브레이커 열림. 잠시 후 다시 시도 가능 */
    LLM_UNAVAILABLE(true),
    /** 오늘 전역 토큰 예산 소진. 내일까지 거절 */
    BUDGET_EXCEEDED(false),
    /** 모델 출력이 형식·길이 규칙을 어김 (1회 재생성 후에도). 다시 시도 가능 */
    INVALID_OUTPUT(true),
    /** 모델이 생성을 거절함. 입력을 바꿔야 함 */
    CONTENT_REFUSED(false),

    // ── coupon-api 가 정함 (메시지로는 오지 않음) ──

    /** 마감 시각까지 결과가 오지 않음 (스케줄러). 다시 시도 가능 */
    TIMEOUT(true),
    /** llm-requests 발행 실패 — API 는 503 으로 바로 응답 */
    PUBLISH_FAILED(true);

    private final boolean retryable;

    LlmFailureReason(boolean retryable) {
        this.retryable = retryable;
    }

    /** 같은 입력으로 다시 시도할 의미가 있는가 (화면의 [다시 시도] 버튼 노출 기준) */
    public boolean isRetryable() {
        return retryable;
    }
}
