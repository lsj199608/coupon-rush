package com.couponrush.llmservice.client;

import com.couponrush.llm.contract.EventCopyCandidate;

import java.util.List;

/** LLM 호출 성공 결과. candidates 는 계약 규칙(길이·개수)을 이미 통과한 값 */
public record LlmCompletion(List<EventCopyCandidate> candidates, String model, int inputTokens, int outputTokens) {

    public LlmCompletion {
        candidates = List.copyOf(candidates);
    }
}
