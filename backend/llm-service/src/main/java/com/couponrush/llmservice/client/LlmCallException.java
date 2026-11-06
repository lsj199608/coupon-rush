package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.LlmFailureReason;

/**
 * 사유가 정해진 LLM 호출 실패. FAILED 결과 메시지가 된다 (Kafka 재시도 대상이 아님 — 다시 처리하면 비용이 또 든다).
 * 이미 모델을 호출한 뒤 실패했다면(예: 출력 형식 위반) 쓴 토큰을 함께 담는다 → 예산에 잡힌다.
 */
public class LlmCallException extends RuntimeException {

    private final LlmFailureReason reason;
    private final String model;
    private final int inputTokens;
    private final int outputTokens;

    public LlmCallException(LlmFailureReason reason, String message, String model, int inputTokens, int outputTokens,
                            Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public LlmCallException(LlmFailureReason reason, String message) {
        this(reason, message, null, 0, 0, null);
    }

    public LlmFailureReason getReason() { return reason; }
    public String getModel() { return model; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
}
